//
// $Id$

package com.threerings.msoy.server;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import com.samskivert.jdbc.RepositoryUnit;
import com.samskivert.jdbc.WriteOnlyUnit;
import com.samskivert.util.Interval;
import com.samskivert.util.Invoker;
import com.samskivert.util.ObjectUtil;
import com.samskivert.util.ResultListener;
import com.samskivert.util.StringUtil;

import com.google.common.collect.Lists;
import com.threerings.underwire.server.persist.EventRecord;
import com.threerings.underwire.web.data.Event;
import com.threerings.util.Name;

import com.threerings.presents.annotation.EventThread;
import com.threerings.presents.annotation.MainInvoker;
import com.threerings.presents.client.InvocationService;
import com.threerings.presents.data.ClientObject;
import com.threerings.presents.data.InvocationCodes;
import com.threerings.presents.dobj.AttributeChangeListener;
import com.threerings.presents.dobj.AttributeChangedEvent;
import com.threerings.presents.dobj.DSet;
import com.threerings.presents.peer.server.CronLogic;
import com.threerings.presents.server.ClientManager;
import com.threerings.presents.server.InvocationException;
import com.threerings.presents.server.InvocationManager;
import com.threerings.presents.server.PresentsDObjectMgr;
import com.threerings.presents.server.PresentsSession;
import com.threerings.presents.server.ReportManager;
import com.threerings.presents.util.PersistingUnit;

import com.threerings.crowd.chat.data.ChatMessage;
import com.threerings.crowd.chat.data.UserMessage;
import com.threerings.crowd.chat.server.SpeakUtil;
import com.threerings.crowd.data.BodyObject;
import com.threerings.crowd.data.PlaceObject;
import com.threerings.crowd.server.BodyManager;
import com.threerings.crowd.server.PlaceManager;
import com.threerings.crowd.server.PlaceRegistry;

import com.threerings.msoy.chat.data.MsoyChatCodes;
import com.threerings.msoy.data.MemberExperience;
import com.threerings.msoy.data.MemberLocation;
import com.threerings.msoy.data.MemberObject;
import com.threerings.msoy.data.MsoyCodes;
import com.threerings.msoy.data.all.MemberName;
import com.threerings.msoy.server.persist.BatchInvoker;
import com.threerings.msoy.server.persist.MemberRepository;
import com.threerings.msoy.server.util.ServiceUnit;

import com.threerings.msoy.peer.server.MsoyPeerManager;

import com.threerings.msoy.admin.server.MsoyAdminManager;
import com.threerings.msoy.badge.server.BadgeManager;
import com.threerings.msoy.game.server.PlayerNodeActions;
import com.threerings.msoy.group.server.persist.GroupRepository;
import com.threerings.msoy.item.data.ItemCodes;
import com.threerings.msoy.item.data.all.Avatar;
import com.threerings.msoy.item.data.all.Item;
import com.threerings.msoy.item.data.all.ItemIdent;
import com.threerings.msoy.item.server.ItemLogic;
import com.threerings.msoy.item.server.ItemManager;
import com.threerings.msoy.mail.server.MailLogic;
import com.threerings.msoy.notify.data.GenericNotification;
import com.threerings.msoy.notify.data.LevelUpNotification;
import com.threerings.msoy.notify.data.Notification;
import com.threerings.msoy.notify.server.NotificationManager;
import com.threerings.msoy.person.gwt.FeedMessageType;
import com.threerings.msoy.person.server.FeedLogic;
import com.threerings.msoy.person.server.persist.ProfileRepository;
import com.threerings.msoy.profile.gwt.Profile;
import com.threerings.msoy.room.data.EntityMemories;
import com.threerings.msoy.room.data.MemberInfo;
import com.threerings.msoy.room.data.MsoySceneModel;
import com.threerings.msoy.room.data.RoomObject;
import com.threerings.msoy.room.server.persist.MemoriesRecord;
import com.threerings.msoy.room.server.persist.MemoryRepository;
import com.threerings.msoy.room.server.persist.MsoySceneRepository;
import com.threerings.msoy.room.server.persist.SceneRecord;
import com.threerings.msoy.underwire.server.SupportLogic;

import static com.threerings.msoy.Log.log;

/**
 * Manage msoy members.
 */
@Singleton @EventThread
public class MemberManager
    implements MemberLocator.Observer, MemberProvider
{
    /** Identifies a report that contains a dump of client object info. */
    public static final String CLIENTS_REPORT_TYPE = "clients";

    @Inject public MemberManager (InvocationManager invmgr, MsoyPeerManager peerMan,
                                  MemberLocator locator, ReportManager repMan)
    {
        // register our bootstrap invocation service
        invmgr.registerDispatcher(new MemberDispatcher(this), MsoyCodes.MSOY_GROUP);

        // register to hear when members logon and off
        locator.addObserver(this);

        // register to hear when members are forwarded between nodes
        peerMan.memberFwdObs.add(new MsoyPeerManager.MemberForwardObserver() {
            public void memberWillBeSent (String node, MemberObject memobj) {
                // flush the transient bits in our metrics as we will snapshot and send this data
                // before we depart our current room (which is when the are normally saved)
                MemberLocal mlocal = memobj.getLocal(MemberLocal.class);
                mlocal.metrics.save(memobj);

                // update the number of active seconds they've spent online
                MsoySession mclient = (MsoySession)_clmgr.getClient(memobj.username);
                if (mclient != null) {
                    mlocal.sessionSeconds += mclient.getSessionSeconds();
                    // let our client handler know that the session is not over but rather is being
                    // forwarded to another server
                    mclient.setSessionForwarded(true);
                }
            }
        });

        // register a reporter for the clients report
        repMan.registerReporter(CLIENTS_REPORT_TYPE, new ReportManager.Reporter() {
            public void appendReport (StringBuilder buf, long now, long sinceLast, boolean reset) {
                for (ClientObject clobj : _clmgr.clientObjects()) {
                    if (!(clobj instanceof BodyObject)) {
                        buf.append("- ").append(clobj.getClass().getSimpleName()).append("\n");
                    } else {
                        appendBody(buf, (BodyObject)clobj);
                    }
                }
            }
            protected void appendBody (StringBuilder buf, BodyObject body) {
                buf.append("- ").append(body.getClass().getSimpleName()).append(" [id=");
                Name vname = body.getVisibleName();
                if (vname instanceof MemberName) {
                    buf.append(((MemberName)vname).getMemberId());
                } else {
                    buf.append(vname);
                }
                buf.append(", status=").append(body.status);
                buf.append(", loc=").append(body.location).append("]\n");
            }
        });
    }

    /**
     * Prepares our member manager for operation.
     */
    public void init ()
    {
        // unit to load greeters
        final Invoker.Unit loadGreeters = new Invoker.Unit("loadGreeterIds") {
            public boolean invoke () {
                List<Integer> greeterIds = _memberRepo.loadGreeterIds();
                synchronized (_snapshotLock) {
                    _greeterIdsSnapshot = greeterIds;
                }
                return false;
            }

            public long getLongThreshold () {
                return 10 * 1000;
            }
        };

        // do the initial load on the main thread so we are ready to go
        _greeterIdsSnapshot = _memberRepo.loadGreeterIds();

        // create the interval to post the unit
        _greeterIdsInvalidator = new Interval() {
            @Override public void expired() {
                _batchInvoker.postUnit(loadGreeters);
            }
        };

        // loading all the greeter ids is expensive, so do it infrequently
        _greeterIdsInvalidator.schedule(GREETERS_REFRESH_PERIOD, true);

        _ppSnapshot = PopularPlacesSnapshot.takeSnapshot(_omgr, _peerMan, getGreeterIdsSnapshot());
        _ppInvalidator = new Interval(_omgr) {
            @Override public void expired() {
                final PopularPlacesSnapshot newSnapshot =
                    PopularPlacesSnapshot.takeSnapshot(_omgr, _peerMan, getGreeterIdsSnapshot());
                synchronized (_snapshotLock) {
                    _ppSnapshot = newSnapshot;
                }
            }
        };
        _ppInvalidator.schedule(POP_PLACES_REFRESH_PERIOD, true);

        // schedule member-related periodic jobs (note: these run on background threads)
        _cronLogic.scheduleEvery(1, new Runnable() {
            public void run () {
                _memberRepo.purgeEntryVectors();
            }
        });
        _cronLogic.scheduleEvery(1, new Runnable() {
            public void run () {
                long now = System.currentTimeMillis();
                List<Integer> weakIds = _memberRepo.loadExpiredWeakPermaguestIds(now);
                if (!weakIds.isEmpty()) {
                    _memberLogic.deleteMembers(weakIds);
                    int remaining = _memberRepo.countExpiredWeakPermaguestIds(now);
                    log.info("Purged weak permaguests", "count", weakIds.size(),
                        "remaining", remaining, "ids", weakIds);
                }
            }
        });
    }

    /**
     * Returns the most recently generated popular places snapshot.
     */
    public PopularPlacesSnapshot getPPSnapshot ()
    {
        synchronized (_snapshotLock) {
            return _ppSnapshot;
        }
    }

    /**
     * Returns the level finder object.
     */
    public LevelFinder getLevelFinder ()
    {
        return _levels;
    }

    public void addExperience (final MemberObject memObj, final MemberExperience newExp)
    {
        memObj.startTransaction();
        try {
            // If we're at our limit of experiences, remove the oldest.
            if (memObj.experiences.size() >= MAX_EXPERIENCES) {
                MemberExperience oldest = null;
                for (MemberExperience experience : memObj.experiences) {
                    if (oldest == null ||
                            experience.getDateOccurred().compareTo(oldest.getDateOccurred()) < 0) {
                        oldest = experience;
                    }
                }
                memObj.removeFromExperiences(oldest.getKey());
            }

            // Add the new experience
            memObj.addToExperiences(newExp);
        } finally {
            memObj.commitTransaction();
        }
    }

    // from interface MemberLocator.Observer
    public void memberLoggedOn (final MemberObject member)
    {
        if (member.isViewer()) {
            return;
        }

        // add a listener for changes to accumulated flow so that the member's level can be updated
        // as necessary
        member.addListener(new AttributeChangeListener() {
            public void attributeChanged (final AttributeChangedEvent event) {
                if (MemberObject.ACC_COINS.equals(event.getName())) {
                    checkCurrentLevel(member);
                }
            }
        });

        // check their current level now in case they got flow while they were offline
        checkCurrentLevel(member);

        // update badges
        _badgeMan.updateBadges(member);

        // TODO: give a time estimate, add custom message?
        if (_adminMan.willRebootSoon()) {
            _notifyMan.notify(
                member, new GenericNotification("m.reboot_soon", Notification.SYSTEM));
        }
    }

    // from interface MemberLocator.Observer
    public void memberLoggedOff (final MemberObject member)
    {
        // nada
    }

    // from interface MemberProvider
    public void inviteToBeFriend (final ClientObject caller, final int friendId,
                                  final InvocationService.ResultListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;
        _invoker.postUnit(new ServiceUnit("inviteToBeFriend", listener) {
            @Override public void invokePersistent () throws Exception {
                _autoFriended = _memberLogic.inviteToBeFriend(user.getMemberId(), friendId);
            }
            @Override public void handleSuccess () {
                reportRequestProcessed(_autoFriended);
            }
            protected boolean _autoFriended;
        });
    }

    // from interface MemberProvider
    public void inviteAllToBeFriends (final ClientObject caller, final int memberIds[],
                                      final InvocationService.ConfirmListener listener)
    {
        final MemberObject user = (MemberObject) caller;
        if (memberIds.length == 0) {
            log.warning("Called inviteAllToBeFriends with no member ids", "caller", caller.who());
            listener.requestProcessed();
        }
        _invoker.postUnit(new ServiceUnit("inviteAllToBeFriends", listener) {
            List<Exception> failures = Lists.newArrayList();
            @Override public void invokePersistent () throws Exception {
                for (int friendId : memberIds) {
                    try {
                        _memberLogic.inviteToBeFriend(user.getMemberId(), friendId);
                    } catch (Exception ex) {
                        failures.add(ex);
                    }
                }
                // only report failure if no friend requests were sent
                if (failures.size() == memberIds.length) {
                    throw failures.get(0);
                }
            }
            @Override public void handleSuccess () {
                reportRequestProcessed();
                _eventLog.batchFriendRequestSent(
                    user.getMemberId(), memberIds.length, failures.size());
            }
        });
    }

    // from interface MemberProvider
    public void bootFromPlace (final ClientObject caller, final int booteeId,
                               final InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;
        if (user.location == null) {
            throw new InvocationException(InvocationCodes.INTERNAL_ERROR);
//            // TEST: let's pretend that we KNOW that they're in a game... just move them home
//            MemberObject bootee = _locator.lookupMember(booteeId);
//            _screg.moveBody(bootee, bootee.getHomeSceneId());
//            listener.requestProcessed();
//            return;
        }

        final PlaceManager pmgr = _placeReg.getPlaceManager(user.location.placeOid);
        if (!(pmgr instanceof BootablePlaceManager)) {
            throw new InvocationException(InvocationCodes.INTERNAL_ERROR);
        }

        final String response = ((BootablePlaceManager) pmgr).bootFromPlace(user, booteeId);
        if (response == null) {
            listener.requestProcessed();
        } else {
            listener.requestFailed(response);
        }
    }

    // from interface MemberProvider
    public void getHomeId (final ClientObject caller, final byte ownerType, final int ownerId,
                           final InvocationService.ResultListener listener)
        throws InvocationException
    {
        _invoker.postUnit(new PersistingUnit("getHomeId", listener) {
            @Override public void invokePersistent () throws Exception {
                _homeId = _memberLogic.getHomeId(ownerType, ownerId);
            }
            @Override public void handleSuccess () {
                if (_homeId == null) {
                    handleFailure(new InvocationException("m.no_such_user"));
                } else {
                    reportRequestProcessed(_homeId);
                }
            }
            protected Integer _homeId;
        });
    }

    // from interface MemberProvider
    public void getCurrentMemberLocation (final ClientObject caller, final int memberId,
                                          final InvocationService.ResultListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;

        // ensure that the other member is a full friend (or we're support)
        if (!user.tokens.isSupport() &&
                !user.getLocal(MemberLocal.class).friendIds.contains(memberId)) {
            throw new InvocationException("e.not_a_friend");
        }

        final MemberLocation memloc = _peerMan.getMemberLocation(memberId);
        if (memloc == null) {
            throw new InvocationException("e.not_online");
        }
        listener.requestProcessed(memloc);
    }

    // from interface MemberProvider
    public void inviteToFollow (final ClientObject caller, final int memberId,
                                final InvocationService.InvocationListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;

        // make sure the target member is online and in the same room as the requester
        final MemberObject target = _locator.lookupMember(memberId);
        if (target == null || !ObjectUtil.equals(user.location, target.location)) {
            throw new InvocationException("e.follow_not_in_room");

        } else if (target.isAway()) {
            throw new InvocationException("e.follow_not_available");
        }

        // issue the follow invitation to the target
        _notifyMan.notifyFollowInvite(target, user.memberName);
    }

    // from interface MemberProvider
    public void followMember (final ClientObject caller, final int memberId,
                              final InvocationService.InvocationListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;

        // if the caller is requesting to clear their follow, do so
        if (memberId == 0) {
            if (user.following != null) {
                MemberNodeActions.removeFollower(user.following.getMemberId(), user.getMemberId());
                user.setFollowing(null);
            }
            return;
        }

        // Make sure the target isn't bogus
        final MemberObject target = _locator.lookupMember(memberId);
        if (target == null) {
            throw new InvocationException("e.follow_invite_expired");
        }

        // Wire up both the leader and follower
        if (!target.followers.containsKey(user.getMemberId())) {
            log.debug("Adding follower", "follower", user.who(), "target", target.who());
            target.addToFollowers(user.memberName);
        }
        user.setFollowing(target.memberName);
    }

    // from interface MemberProvider
    public void ditchFollower (ClientObject caller, int followerId,
                               InvocationService.InvocationListener listener)
        throws InvocationException
    {
        MemberObject leader = (MemberObject) caller;

        if (followerId == 0) { // Clear all followers
            for (MemberName follower : leader.followers) {
                MemberObject fmo = _locator.lookupMember(follower.getMemberId());
                if (fmo != null) {
                    fmo.setFollowing(null);
                }
            }
            leader.setFollowers(new DSet<MemberName>());

        } else { // Ditch a single follower
            if (leader.followers.containsKey(followerId)) {
                leader.removeFromFollowers(followerId);
            }
            MemberObject follower = _locator.lookupMember(followerId);
            if (follower != null && follower.following != null &&
                follower.following.getMemberId() == leader.getMemberId()) {
                follower.setFollowing(null);
            }
        }
    }

    // from interface MemberProvider
    public void setAway (
        ClientObject caller, String message, InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;
        user.setAwayMessage(message);
        _bodyMan.updateOccupantInfo(user, new MemberInfo.Updater<MemberInfo>() {
            public boolean update (MemberInfo info) {
                if (info.isAway() == user.isAway()) {
                    return false;
                }
                info.updateIsAway(user);
                return true;
            }
        });
        listener.requestProcessed();
    }

    // from interface MemberProvider
    public void setMuted (
        ClientObject caller, final int muteeId, final boolean muted,
        InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        MemberObject user = (MemberObject) caller;
        final int muterId = user.getMemberId();
        if (muterId == muteeId || muteeId == 0) {
            throw new InvocationException(InvocationCodes.E_INTERNAL_ERROR);
        }
        _invoker.postUnit(new ServiceUnit("setMuted()", listener) {
            @Override public void invokePersistent () throws Exception {
                _memberLogic.setMuted(muterId, muteeId, muted);
            }
        });
    }

    // from interface MemberProvider
    public void setAvatar (final ClientObject caller, final int avatarItemId,
                           InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;
        if (avatarItemId == ((user.avatar == null) ? 0 : user.avatar.itemId)) {
            listener.requestProcessed();
            return;
        }
        if (avatarItemId == 0) {
            // a request to return to the default avatar
            finishSetAvatar(user, null, null, listener);
            return;
        }

        // otherwise, make sure it exists and we own it
        final ItemIdent ident = new ItemIdent(Item.AVATAR, avatarItemId);
        _invoker.postUnit(new PersistingUnit("setAvatar(" + avatarItemId + ")", listener) {
            @Override public void invokePersistent () throws Exception {
                _avatar = (Avatar)_itemLogic.loadItem(ident);
                if (_avatar == null) {
                    throw new InvocationException(ItemCodes.E_NO_SUCH_ITEM);
                }
                if (user.getMemberId() != _avatar.ownerId) { // ensure that they own it
                    log.warning("Not user's avatar", "user", user.which(),
                                "ownerId", _avatar.ownerId);
                    throw new InvocationException(ItemCodes.E_ACCESS_DENIED);
                }
                MemoriesRecord memrec = _memoryRepo.loadMemory(_avatar.getType(), _avatar.itemId);
                _memories = (memrec == null) ? null : memrec.toEntry();
            }

            @Override public void handleSuccess () {
                if (_avatar.equals(user.avatar)) {
                    ((InvocationService.ConfirmListener)_listener).requestProcessed();
                    return;
                }
                finishSetAvatar(user, _avatar, _memories,
                                (InvocationService.ConfirmListener)_listener);
            }

            protected EntityMemories _memories;
            protected Avatar _avatar;
        });
    }

    // from interface MemberProvider
    public void setDisplayName (final ClientObject caller, final String name,
                                InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final MemberObject user = (MemberObject) caller;
        _invoker.postUnit(new ServiceUnit("setDisplayName", listener,
                                          "user", user.who(), "name", name) {
            @Override public void invokePersistent () throws Exception {
                _memberLogic.setDisplayName(user.getMemberId(), name, user.tokens.isSupport());
            }
        });
    }

    // from interface MemberProvider
    public void getDisplayName (final ClientObject caller, final int memberId,
                                final InvocationService.ResultListener listener)
        throws InvocationException
    {
        _invoker.postUnit(new PersistingUnit("getDisplayName", listener, "mid", memberId) {
            @Override public void invokePersistent () throws Exception {
                _displayName = String.valueOf(_memberRepo.loadMemberName(memberId));
            }
            @Override public void handleSuccess () {
                reportRequestProcessed(_displayName);
            }
            protected String _displayName;
        });
    }

    // from interface MemberProvider
    public void acknowledgeWarning (final ClientObject caller)
    {
        final MemberObject user = (MemberObject) caller;
        _invoker.postUnit(new WriteOnlyUnit("acknowledgeWarning(" + user.getMemberId() + ")") {
            @Override public void invokePersist () throws Exception {
                _memberRepo.clearMemberWarning(user.getMemberId());
            }
        });
    }

    // from interface MemberProvider
    public void setHomeSceneId (final ClientObject caller, final int ownerType, final int ownerId,
                                final int sceneId, final InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final MemberObject member = (MemberObject) caller;
        _invoker.postUnit(new PersistingUnit("setHomeSceneId", listener, "who", member.who()) {
            @Override public void invokePersistent () throws Exception {
                final int memberId = member.getMemberId();
                final SceneRecord scene = _sceneRepo.loadScene(sceneId);
                if (scene.ownerType == MsoySceneModel.OWNER_TYPE_MEMBER) {
                    if (scene.ownerId == memberId) {
                        _memberRepo.setHomeSceneId(memberId, sceneId);
                    } else {
                        throw new InvocationException("e.not_room_owner");
                    }
                } else if (scene.ownerType == MsoySceneModel.OWNER_TYPE_GROUP) {
                    if (member.isGroupManager(scene.ownerId)) {
                        _groupRepo.setHomeSceneId(scene.ownerId, sceneId);
                    } else {
                        throw new InvocationException("e.not_room_manager");
                    }
                } else {
                    log.warning("Unknown scene model owner type [sceneId=" +
                        scene.sceneId + ", ownerType=" + scene.ownerType + "]");
                    throw new InvocationException(InvocationCodes.INTERNAL_ERROR);
                }
            }
            @Override public void handleSuccess () {
                if (ownerType == MsoySceneModel.OWNER_TYPE_MEMBER) {
                    member.setHomeSceneId(sceneId);
                }
                super.handleSuccess();
            }
        });
    }

    // from interface MemberProvider
    public void complainMember (ClientObject caller, final int memberId, String complaint)
    {
        MemberObject target = _locator.lookupMember(memberId);
        complainMember((BodyObject) caller, memberId,  complaint,
            (target != null) ? target.getMemberName() : null);
    }

    /**
     * Compile server-side information for a complaint against a MemberObject or a PlayerObject
     * and file it with the Underwire system. Note that this method must be called on the dobj
     * thread. The target name is optional (only available when the complainee is online).
     */
    public void complainMember (
        final BodyObject complainer, final int targetId,
        String complaint, MemberName optTargetName)
    {
        MemberName complainerName = (MemberName) complainer.getVisibleName();

        final EventRecord event = new EventRecord();
        event.source = Integer.toString(complainerName.getMemberId());
        event.sourceHandle = complainerName.toString();
        event.status = Event.OPEN;
        event.subject = complaint;

        // format and provide the complainer's chat history
        SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss");
        StringBuilder chatHistory = new StringBuilder();
        for (ChatMessage msg : SpeakUtil.getChatHistory(complainerName)) {
            UserMessage umsg = (UserMessage)msg;
            chatHistory.append(df.format(new Date(umsg.timestamp))).append(' ').
                append(StringUtil.pad(MsoyChatCodes.XLATE_MODES[umsg.mode], 10)).append(' ').
                append(umsg.speaker);
            if (umsg.speaker instanceof MemberName) {
                int memberId = ((MemberName)umsg.speaker).getMemberId();
                chatHistory.append('(').append(memberId).append(')');
            }
            chatHistory.append(": ").append(umsg.message).append('\n');
        }
        event.chatHistory = chatHistory.toString();

        if (optTargetName != null) {
            event.targetHandle = optTargetName.toString();
            event.target = Integer.toString(optTargetName.getMemberId());
        }

        _invoker.postUnit(new Invoker.Unit("addComplaint") {
            @Override public boolean invoke () {
                try {
                    _supportLogic.addComplaint(event, targetId);
                } catch (Exception e) {
                    log.warning("Failed to add complaint event [event=" + event + "].", e);
                    _failed = true;
                }
                return true;
            }
            @Override public void handleResult () {
                SpeakUtil.sendFeedback(complainer, MsoyCodes.GENERAL_MSGS,
                        _failed ? "m.complain_fail" : "m.complain_success");
            }
            protected boolean _failed = false;
        });
    }

    // from interface MemberProvider
    public void updateStatus (
        ClientObject caller, String status, InvocationService.InvocationListener listener)
        throws InvocationException
    {
        final MemberObject member = (MemberObject) caller;
        final String commitStatus = StringUtil.truncate(status, Profile.MAX_STATUS_LENGTH);
        _invoker.postUnit(new PersistingUnit("updateStatus", listener, "who", member.who()) {
            @Override public void invokePersistent () throws Exception {
                _profileRepo.updateHeadline(member.getMemberId(), commitStatus);
            }
            @Override public void handleSuccess () {
                member.setHeadline(commitStatus);
                MemberNodeActions.updateFriendEntries(member);
            }
        });
    }

    /**
     * Broadcast a notification to all members, on this server only.
     */
    public void notifyAll (Notification note)
    {
        for (ClientObject clobj : _clmgr.clientObjects()) {
            if (clobj instanceof MemberObject) {
                MemberObject mem = (MemberObject) clobj;
                if (!mem.isViewer()) {
                    _notifyMan.notify(mem, note);
                }
            }
        }
    }

    /**
     * Check if the member's accumulated flow level matches up with their current level, and update
     * their current level if necessary
     */
    public void checkCurrentLevel (final MemberObject member)
    {
        int level = _levels.findLevel(member.accCoins);

        if (member.level < level) {
            final int oldLevel = member.level;
            final int newLevel = level;
            final int memberId = member.getMemberId();

            // update their level now so that we don't come along and do this again while the
            // invoker is off writing things to the database
            member.setLevel(level);

            _invoker.postUnit(new RepositoryUnit("updateLevel") {
                @Override public void invokePersist () throws Exception {
                    // record the new level
                    _memberRepo.setUserLevel(memberId, newLevel);
                    // mark the level gain in their feed
                    _feedLogic.publishMemberMessage(
                        memberId, FeedMessageType.FRIEND_GAINED_LEVEL, newLevel);
                    // see if we should award a bar to anyone
                    _memberLogic.maybeAwardFriendBar(memberId, oldLevel, newLevel);
                }
                @Override public void handleSuccess () {
                    _notifyMan.notify(member, new LevelUpNotification(newLevel));
                }
                @Override public void handleFailure (final Exception pe) {
                    log.warning("Unable to set user level.",
                        "memberId", member.getMemberId(), "level", newLevel);
                }
            });
        }
    }

    /**
     * Boots a player from the server.  Must be called on the dobjmgr thread.
     *
     * @return true if the player was found and booted successfully
     */
    public boolean bootMember (final int memberId)
    {
        final MemberObject mobj = _locator.lookupMember(memberId);
        if (mobj != null) {
            final PresentsSession pclient = _clmgr.getClient(mobj.username);
            if (pclient != null) {
                pclient.endSession();
                return true;
            }
        }
        return false;
    }

    /**
     * Updates the runtime information for an avatar change then finally commits the change to the
     * database.
     */
    protected void finishSetAvatar (
        final MemberObject user, final Avatar avatar, EntityMemories memories,
        final InvocationService.ConfirmListener listener)
    {
        final Avatar prev = user.avatar;

        // now we need to make sure that the two avatars have a reasonable touched time
        user.startTransaction();
        try {
            // unset the current avatar to avoid busy-work in avatarUpdatedOnPeer, but
            // we'll set the new avatar at the bottom...
            user.avatar = null;

            // NOTE: we are not updating the used/location fields of the cached avatars,
            // I don't think it's necessary, but it'd be a simple matter here...
            final long now = System.currentTimeMillis();
            if (prev != null) {
                prev.lastTouched = now;
                _itemMan.avatarUpdatedOnPeer(user, prev);
            }
            if (avatar != null) {
                avatar.lastTouched = now + 1; // the new one should be more recently touched
                _itemMan.avatarUpdatedOnPeer(user, avatar);
            }

            // now set the new avatar
            user.setAvatar(avatar);
            user.actorState = null; // clear out their state
            user.getLocal(MemberLocal.class).memories = memories;

            // check if this player is already in a room (should be the case)
            if (memories != null) {
                PlaceManager pmgr = _placeReg.getPlaceManager(user.getPlaceOid());
                if (pmgr != null) {
                    PlaceObject plobj = pmgr.getPlaceObject();
                    if (plobj instanceof RoomObject) {
                        // if so, make absolutely sure the avatar memories are in place in the
                        // room before we update the occupant info (which triggers the avatar
                        // media change on the client).
                        user.getLocal(MemberLocal.class).putAvatarMemoriesIntoRoom(
                            (RoomObject)plobj);
                    }
                    // if the player wasn't in a room, the avatar memories will just sit in
                    // MemberLocal storage until they do enter a room, which is proper
                }
            }
            _bodyMan.updateOccupantInfo(user, new MemberInfo.AvatarUpdater(user));

        } finally {
            user.commitTransaction();
        }
        listener.requestProcessed();

        // this just fires off an invoker unit, we don't need the result, log it
        _itemMan.updateItemUsage(
            user.getMemberId(), prev, avatar, new ResultListener.NOOP<Void>() {
            @Override public void requestFailed (final Exception cause) {
                log.warning("Unable to update usage from an avatar change.");
            }
        });

        // now fire off a unit to update the avatar information in the database
        _invoker.postUnit(new WriteOnlyUnit("updateAvatar") {
            @Override public void invokePersist () throws Exception {
                _memberRepo.configureAvatarId(
                    user.getMemberId(), (avatar == null) ? 0 : avatar.itemId);
            }
            @Override public void handleFailure (Exception pe) {
                log.warning("configureAvatarId failed", "user", user.which(), "avatar", avatar, pe);
            }
        });
    }

    /**
     * Returns the most recently loaded set of greeter ids, sorted by last session time. This is
     * only used to construct the popular places snapshot, which figures out which greeters are
     * currently online and sorts and caches a separate list.
     */
    protected List<Integer> getGreeterIdsSnapshot ()
    {
        // using the same monitor here should be ok as the block is only 2 atoms on a 64 bit OS
        synchronized (_snapshotLock) {
            return _greeterIdsSnapshot;
        }
    }

    /** An internal object on which we synchronize to update/get snapshots. */
    protected final Object _snapshotLock = new Object();

    /** An interval that updates the popular places snapshot every so often. */
    protected Interval _ppInvalidator;

    /** The most recent summary of popular places in the whirled. */
    protected PopularPlacesSnapshot _ppSnapshot;

    /** Interval to update the greeter ids snapshot. */
    protected Interval _greeterIdsInvalidator;

    /** Snapshot of all currently configured greeters, sorted by last online. Refreshed
     * periodically. */
    protected List<Integer> _greeterIdsSnapshot;

    /** Coins to level lookup. */
    protected LevelFinder _levels = new LevelFinder();

    // dependencies
    @Inject protected @BatchInvoker Invoker _batchInvoker;
    @Inject protected @MainInvoker Invoker _invoker;
    @Inject protected BadgeManager _badgeMan;
    @Inject protected BodyManager _bodyMan;
    @Inject protected MsoyManager _msoyMan;
    @Inject protected ClientManager _clmgr;
    @Inject protected CronLogic _cronLogic;
    @Inject protected FeedLogic _feedLogic;
    @Inject protected GroupRepository _groupRepo;
    @Inject protected ItemLogic _itemLogic;
    @Inject protected ItemManager _itemMan;
    @Inject protected MailLogic _mailLogic;
    @Inject protected MemberLocator _locator;
    @Inject protected MemberLogic _memberLogic;
    @Inject protected MemberRepository _memberRepo;
    @Inject protected MemoryRepository _memoryRepo;
    @Inject protected MsoyAdminManager _adminMan;
    @Inject protected MsoyEventLogger _eventLog;
    @Inject protected MsoyPeerManager _peerMan;
    @Inject protected MsoySceneRepository _sceneRepo;
    @Inject protected NotificationManager _notifyMan;
    @Inject protected PlaceRegistry _placeReg;
    @Inject protected PlayerNodeActions _playerActions;
    @Inject protected PresentsDObjectMgr _omgr;
    @Inject protected ProfileRepository _profileRepo;
    @Inject protected SupportLogic _supportLogic;

    /** The frequency with which we recalculate our popular places snapshot. */
    protected static final long POP_PLACES_REFRESH_PERIOD = 30*1000;

    /** The frequency with which we recalculate our greeter ids snapshot. */
    protected static final long GREETERS_REFRESH_PERIOD = 30 * 60 * 1000;

    /** Maximum number of experiences we will keep track of per user. */
    protected static final int MAX_EXPERIENCES = 20;
}
