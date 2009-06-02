//
// $Id$

package com.threerings.msoy.chat.client {

import com.threerings.util.StringUtil;

import com.threerings.crowd.data.BodyObject;
import com.threerings.crowd.util.CrowdContext;

import com.threerings.crowd.chat.client.BroadcastHandler;
import com.threerings.crowd.chat.client.SpeakService;
import com.threerings.crowd.chat.data.ChatCodes;

import com.threerings.msoy.data.MemberObject;
import com.threerings.msoy.data.MsoyCodes;
import com.threerings.msoy.data.MsoyTokenRing;
import com.threerings.msoy.client.DeploymentConfig;
import com.threerings.msoy.client.MsoyContext;

import com.threerings.msoy.game.data.PlayerObject;
import com.threerings.msoy.game.client.GameContext;

/**
 * Msoy version of broadcasting. Allow non-admins to access the command but shows a confirmation
 * panel (that will charge them money and then send a slightly less important looking broadcast
 * message).
 */
public class MsoyBroadcastHandler extends BroadcastHandler
{
    override public function checkAccess (user :BodyObject) :Boolean
    {
        // no permaguests
        return ((user is MemberObject) && !MemberObject(user).isPermaguest()) ||
            ((user is PlayerObject) && !PlayerObject(user).isPermaguest());
    }

    override public function handleCommand (
        ctx :CrowdContext, speakSvc :SpeakService,
        cmd :String, args :String, history :Array) :String
    {
        // TODO SUBSCRIPTION
        if (DeploymentConfig.devDeployment &&
                !MsoyTokenRing(getBody(ctx).getTokens()).isSubscriberPlus()) {
            getMsoyContext(ctx).displayFeedback(MsoyCodes.GENERAL_MSGS, "e.subscription_required");
            return ChatCodes.SUCCESS; // because we want to clear the chat entry field
        }

        return super.handleCommand(ctx, speakSvc, cmd, args, history);
    }

    override protected function doBroadcast (ctx :CrowdContext, msg :String) :void
    {
        // if they have access to the normal broadcast, that's what they get
        if (super.checkAccess(getBody(ctx))) {
            // if the whole of the message is pay, or starts with pay, let an admin access the pay
            if ("pay" === StringUtil.trim(StringUtil.truncate(msg, 4)).toLowerCase()) {
                msg = msg.substring(4);
            } else {
                super.doBroadcast(ctx, msg);
                return;
            }
        }
        new BroadcastPanel(getMsoyContext(ctx), msg);
    }

    protected function getBody (ctx :CrowdContext) :BodyObject
    {
        return BodyObject(ctx.getClient().getClientObject());
    }

    protected function getMsoyContext (ctx :CrowdContext) :MsoyContext
    {
        // this is fucked-up, but due to the fact that we actually have two ChatDirectors...
        var mctx :MsoyContext = ctx as MsoyContext;
        if (mctx == null) {
            mctx = GameContext(ctx).getWorldContext();
        }
        return mctx;
    }
}
}
