//
// $Id$

package com.threerings.msoy.game.client {

import com.threerings.util.Log;

import com.threerings.presents.client.ClientEvent;

import com.threerings.msoy.client.ControlBar;
import com.threerings.msoy.client.HeaderBar;
import com.threerings.msoy.client.MsoyController;
import com.threerings.msoy.client.MsoyParameters;
import com.threerings.msoy.client.TopPanel;
import com.threerings.msoy.data.MsoyCodes;

import com.threerings.msoy.game.client.LobbyService;
import com.threerings.msoy.game.data.MsoyGameConfig;

/**
 * Customizes the MsoyController for operation in the standalone game client.
 */
public class GameController extends MsoyController
{
    public function GameController (gctx :GameContext, topPanel :TopPanel)
    {
        super(gctx.getMsoyContext(), topPanel);
        _gctx = gctx;
    }

    // from MsoyController
    override public function handleClosePlaceView () : void
    {
        // give the handlers a chance to popup
        if (!sanctionClosePlaceView()) {
            continue;
        }

        // if we're in the whirled, closing means closing the flash client totally
        _mctx.getMsoyClient().closeClient();
    }

    // from ClientObserver
    override public function clientDidLogon (event :ClientEvent) :void
    {
        super.clientDidLogon(event);

        var params :Object = MsoyParameters.get();
        if (null != params["gameLocation"]) {
            var gameOid :int = int(params["gameLocation"]);
            log.info("Entering game [oid=" + gameOid + "].");
            _gctx.getLocationDirector().moveTo(gameOid);
        } else if (null != params["gameId"]) {
            var gameId :int = int(params["gameId"]);
            log.info("Entering lobby [oid=" + gameOid + "].");
            joinGameLobby(gameId); // TODO: handle mode=(m|a|s)
        }
    }

    protected function joinGameLobby (gameId :int) :void
    {
        var lsvc :LobbyService = (_gctx.getClient().requireService(LobbyService) as LobbyService);
        lsvc.identifyLobby(_gctx.getClient(), gameId,
            _mctx.resultListener(gotLobbyOid, MsoyCodes.GAME_MSGS));
    }

    protected function gotLobbyOid (lobbyOid :int) :void
    {
        // this will create a panel and add it to the side panel on the top level
        var ctrl :LobbyController = new LobbyController(_gctx, lobbyOid, lobbyCleared, true);
    }

    protected function lobbyCleared (inGame :Boolean, closedByUser :Boolean) :void
    {
        // TODO
    }

    protected function playNow (mode :int) :void
    {
        // TODO
    }

    protected function lobbyLoaded (groupId :int) :void
    {
        // TODO
    }

    // from MsoyController
    override protected function updateTopPanel (headerBar :HeaderBar, controlBar :ControlBar) :void
    {
        super.updateTopPanel(headerBar, controlBar);

        // TODO: what to do when we're in the lobby?

        // if we're in a game, display the game name and activate the back button
        var cfg :MsoyGameConfig =
            (_gctx.getLocationDirector().getPlaceController().getPlaceConfig() as MsoyGameConfig);
        if (cfg != null) {
            _mctx.getMsoyClient().setWindowTitle(cfg.game.name);
            headerBar.setLocationName(cfg.game.name);
            headerBar.setOwnerLink("");
        }
    }

    protected var _gctx :GameContext;

    private static const log :Log = Log.getLog(GameController);
}
}
