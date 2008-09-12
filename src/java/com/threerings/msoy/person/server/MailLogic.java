//
// $Id$

package com.threerings.msoy.person.server;

import java.io.StringWriter;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import com.samskivert.util.ArrayIntSet;
import com.samskivert.util.IntSet;
import com.samskivert.util.Invoker;
import com.samskivert.velocity.VelocityUtil;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import com.threerings.presents.annotation.BlockingThread;
import com.threerings.presents.annotation.MainInvoker;

import com.threerings.presents.dobj.RootDObjectManager;

import com.threerings.msoy.server.MemberNodeActions;
import com.threerings.msoy.server.ServerConfig;
import com.threerings.msoy.server.ServerMessages;
import com.threerings.msoy.server.persist.MemberRecord;
import com.threerings.msoy.server.persist.MemberRepository;
import com.threerings.msoy.server.util.JSONMarshaller;
import com.threerings.msoy.server.util.MailSender;

import com.threerings.msoy.item.data.all.Item;
import com.threerings.msoy.item.data.all.ItemIdent;
import com.threerings.msoy.item.server.ItemLogic;
import com.threerings.msoy.item.server.persist.ItemRecord;
import com.threerings.msoy.item.server.persist.ItemRepository;

import com.threerings.msoy.web.data.ServiceCodes;
import com.threerings.msoy.web.data.ServiceException;

import com.threerings.msoy.mail.gwt.FriendInvitePayload;
import com.threerings.msoy.mail.gwt.MailPayload;
import com.threerings.msoy.mail.gwt.PresentPayload;
import com.threerings.msoy.mail.server.persist.ConvMessageRecord;
import com.threerings.msoy.mail.server.persist.ConversationRecord;
import com.threerings.msoy.mail.server.persist.MailRepository;

import static com.threerings.msoy.Log.log;

/**
 * Provides mail related services to servlets and other blocking thread entities.
 */
@Singleton @BlockingThread
public class MailLogic
{
    /**
     * Sends a friend invitation email from the supplied inviter to the specified member.
     */
    public void sendFriendInvite (int inviterId, int friendId)
        throws ServiceException
    {
        MemberRecord sender = _memberRepo.loadMember(inviterId);
        MemberRecord recip = _memberRepo.loadMember(friendId);
        if (sender == null || recip == null) {
            log.warning("Missing records for friend invite [iid=" + inviterId +
                        ", tid=" + friendId + ", irec=" + sender + ", trec=" + recip + "].");
            throw new ServiceException(ServiceCodes.E_INTERNAL_ERROR);
        }
        String subj = _serverMsgs.getBundle("server").get("m.friend_invite_subject");
        String body = _serverMsgs.getBundle("server").get("m.friend_invite_body");
        startConversation(sender, recip, subj, body, new FriendInvitePayload());
    }

    /**
     * Starts a mail conversation between the specified two parties.
     */
    public void startConversation (MemberRecord sender, MemberRecord recip,
                                   String subject, String body, MailPayload attachment)
        throws ServiceException
    {
        // if the payload is an item attachment, transfer it to the recipient
        processPayload(sender.memberId, recip.memberId, attachment);

        // now start the conversation (and deliver the message)
        _mailRepo.startConversation(recip.memberId, sender.memberId, subject, body, attachment);

        // potentially send a real email to the recipient
        sendMailEmail(sender, recip, subject, body);

        // let recipient know they've got mail
        MemberNodeActions.reportUnreadMail(
            recip.memberId, _mailRepo.loadUnreadConvoCount(recip.memberId));
    }

    /**
     * Continues the specified mail conversation.
     */
    public ConvMessageRecord continueConversation (MemberRecord poster, int convoId, String body,
                                                   MailPayload attachment)
        throws ServiceException
    {
        ConversationRecord conrec = _mailRepo.loadConversation(convoId);
        if (conrec == null) {
            log.warning("Requested to continue non-existent conversation [by=" + poster.who() +
                        ", convoId=" + convoId + "].");
            throw new ServiceException(ServiceCodes.E_INTERNAL_ERROR);
        }

        // make sure this member is a conversation participant
        Long lastRead = _mailRepo.loadLastRead(convoId, poster.memberId);
        if (lastRead == null) {
            log.warning("Request to continue conversation by non-member [who=" + poster.who() +
                        ", convoId=" + convoId + "].");
            throw new ServiceException(ServiceCodes.E_INTERNAL_ERROR);
        }

        // TODO: make sure body.length() is not too long

        // encode the attachment if we have one
        int payloadType = 0;
        byte[] payloadState = null;
        if (attachment != null) {
            payloadType = attachment.getType();
            try {
                payloadState = JSONMarshaller.getMarshaller(
                    attachment.getClass()).getStateBytes(attachment);
            } catch (Exception e) {
                log.warning("Failed to encode message attachment [for=" + poster.who() +
                            ", attachment=" + attachment + "].");
                throw new ServiceException(ServiceCodes.E_INTERNAL_ERROR);
            }
        }

        // if the payload is an item attachment, transfer it to the recipient
        processPayload(poster.memberId, conrec.getOtherId(poster.memberId), attachment);

        // store the message in the repository
        ConvMessageRecord cmr =
            _mailRepo.addMessage(conrec, poster.memberId, body, payloadType, payloadState);

        // update our last read for this conversation to reflect that we've read our message
        _mailRepo.updateLastRead(convoId, poster.memberId, cmr.sent.getTime());

        // let other conversation participant know they've got mail
        int otherId = conrec.getOtherId(poster.memberId);
        MemberNodeActions.reportUnreadMail(otherId, _mailRepo.loadUnreadConvoCount(otherId));

        // potentially send a real email to the recipient
        MemberRecord recip = _memberRepo.loadMember(otherId);
        if (recip != null) {
            String subject = _serverMsgs.getBundle("server").get("m.reply_subject", conrec.subject);
            sendMailEmail(poster, recip, subject, body);
        }

        return cmr;
    }

    /**
     * Sends email to all players who have not opted out of Whirled announcements.
     */
    public void spamPlayers (String subject, String body, int startId, int endId)
    {
        // TODO: if we want to continue to use this mechanism to send mass emails to our members,
        // we will need to farm out the mail deliver task to all nodes in the network so that we
        // don't task one node with sending out a million email messages

        // start with member 1 if we weren't given a higher starting id
        startId = Math.max(startId, 1);

        // if we don't have an endId, go all the way
        if (endId <= 0) {
            endId = Integer.MAX_VALUE;
        }

        // convert the body into proper-ish HTML
        try {
            StringWriter swout = new StringWriter();
            VelocityContext ctx = new VelocityContext();
            ctx.put("base_url", ServerConfig.getServerURL());
            ctx.put("content", body);
            VelocityEngine ve = VelocityUtil.createEngine();
            ve.mergeTemplate("rsrc/email/wrapper/message.html", "UTF-8", ctx, swout);
            body = swout.toString();
        } catch (Exception e) {
            log.warning("Unable to format spam message", e);
            return;
        }

        // loop through 100 members at a time and load up their record and send emails
        final String from = ServerConfig.getFromAddress();
        int found;
        do {
            final IntSet memIds = new ArrayIntSet();
            for (int ii = 0; ii < MEMBERS_PER_LOOP; ii++) {
                final int memberId = ii + startId;
                if (memberId > endId) {
                    break;
                }
                memIds.add(memberId);
            }
            if (memIds.size() == 0) {
                break;
            }

            found = 0;
            for (final MemberRecord mrec : _memberRepo.loadMembers(memIds)) {
                found++;

                if (mrec.isSet(MemberRecord.Flag.NO_ANNOUNCE_EMAIL)) {
                    continue;
                }

                try {
                    _mailer.sendEmail(mrec.accountName, from, subject, body, true);
                } catch (final Exception e) {
                    log.warning("Failed to spam member [subject=" + subject +
                                ", email=" + mrec.accountName + ", error=" + e + "].");
                    // roll on through and try the next one
                }
            }

            startId += MEMBERS_PER_LOOP;
        } while (startId < endId && found > 0);
    }

    /**
     * Handles any side-effects of mail payload delivery. Currently that is only the transfer of an
     * item from the sender to the recipient for {@link PresentPayload}.
     */
    protected void processPayload (final int senderId, final int recipId, MailPayload attachment)
        throws ServiceException
    {
        if (attachment instanceof PresentPayload) {
            ItemIdent ident = ((PresentPayload)attachment).ident;
            ItemRepository<?> repo = _itemLogic.getRepository(ident.type);
            final ItemRecord item = repo.loadItem(ident.itemId);

            // validate that they're allowed to gift this item (these are all also checked on the
            // client so we don't need useful error messages)
            String errmsg = null;
            if (item == null) {
                errmsg = "Trying to gift non-existent item";
            } else if (item.ownerId != senderId) {
                errmsg = "Trying to gift un-owned item";
            } else if (item.used != Item.UNUSED) {
                errmsg = "Trying to gift in-use item";
            }
            if (errmsg != null) {
                log.warning(errmsg + " [sender=" + senderId + ", recip=" + recipId +
                            ", ident=" + ident + "].");
                throw new ServiceException(ServiceCodes.E_INTERNAL_ERROR);
            }

            final ItemRecord oitem = (ItemRecord)item.clone();
            repo.updateOwnerId(item, recipId);

            // notify the item system that the item has moved
            _itemLogic.itemUpdated(oitem, item);
        }
    }

    /**
     * Send an email to a Whirled mail recipient to report that they received a Whirled mail. Does
     * nothing if the recipient has requested not to receive such mails.
     */
    protected void sendMailEmail (MemberRecord sender, MemberRecord recip,
                                  String subject, String body)
    {
        // if they don't want to hear about it, stop now
        if (recip.isSet(MemberRecord.Flag.NO_WHIRLED_MAIL_TO_EMAIL)) {
            return;
        }
        _mailer.sendEmail(recip.accountName, ServerConfig.getFromAddress(), "gotMail",
                          "subject", subject,"sender", sender.name, "senderId", sender.memberId,
                          "body", body, "server_url", ServerConfig.getServerURL());
    }

    @Inject protected RootDObjectManager _omgr;
    @Inject protected ServerMessages _serverMsgs;
    @Inject protected @MainInvoker Invoker _invoker;
    @Inject protected MailSender _mailer;
    @Inject protected ItemLogic _itemLogic;
    @Inject protected MailRepository _mailRepo;
    @Inject protected MemberRepository _memberRepo;

    protected static final int MEMBERS_PER_LOOP = 100;
}
