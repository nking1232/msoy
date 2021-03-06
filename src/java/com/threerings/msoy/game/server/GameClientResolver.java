//
// $Id$

package com.threerings.msoy.game.server;

import com.google.inject.Inject;

import com.threerings.presents.data.ClientObject;

import com.threerings.crowd.server.CrowdClientResolver;

import com.threerings.orth.data.MediaDesc;

import com.threerings.msoy.data.all.VisitorInfo;
import com.threerings.msoy.data.all.VizMemberName;
import com.threerings.msoy.game.data.GameAuthName;
import com.threerings.msoy.game.data.PlayerObject;
import com.threerings.msoy.money.data.all.MemberMoney;
import com.threerings.msoy.money.server.MoneyLogic;
import com.threerings.msoy.peer.server.MsoyPeerManager;
import com.threerings.msoy.person.server.persist.ProfileRecord;
import com.threerings.msoy.person.server.persist.ProfileRepository;
import com.threerings.msoy.server.persist.MemberRecord;
import com.threerings.msoy.server.persist.MemberRepository;

/**
 * Resolves an MSOY Game client's runtime data.
 */
public class GameClientResolver extends CrowdClientResolver
{
    @Override // from PresentsClientResolver
    public ClientObject createClientObject ()
    {
        return new PlayerObject();
    }

    @Override // from PresentsSession
    protected void resolveClientData (ClientObject clobj)
        throws Exception
    {
        super.resolveClientData(clobj);

        GameAuthName authName = (GameAuthName)_username;
        MemberRecord member = _memberRepo.loadMember(authName.getId());
        ProfileRecord precord = _profileRepo.loadProfile(member.memberId);
        MediaDesc photo = (precord == null) ? VizMemberName.DEFAULT_PHOTO : precord.getPhoto();

        // NOTE: we avoid using the dobject setters here because we know the object is not out in
        // the wild and there's no point in generating a crapload of events during user
        // initialization when we know that no one is listening
        PlayerObject plobj = (PlayerObject) clobj;
        plobj.memberName = new VizMemberName(member.name, member.memberId, photo);
        plobj.visitorInfo = new VisitorInfo(member.visitorId, true);

        final MemberMoney money = _moneyLogic.getMoneyFor(authName.getId());
        plobj.coins = money.coins;
        plobj.bars = money.bars;
    }

    @Override
    protected void finishResolution (ClientObject clobj)
    {
        super.finishResolution(clobj);

        PlayerObject plobj = (PlayerObject) clobj;
        // resolve their party info
        plobj.setParty(_peerMan.getPartySummary(plobj.getMemberId()));
    }

    // our dependencies
    @Inject protected MemberRepository _memberRepo;
    @Inject protected MoneyLogic _moneyLogic;
    @Inject protected MsoyPeerManager _peerMan;
    @Inject protected ProfileRepository _profileRepo;
}
