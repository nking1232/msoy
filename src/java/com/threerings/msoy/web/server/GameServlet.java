//
// $Id$

package com.threerings.msoy.web.server;

import java.util.logging.Level;

import com.samskivert.io.PersistenceException;
import com.samskivert.util.StringUtil;

import com.threerings.presents.data.InvocationCodes;

import com.threerings.msoy.game.data.MsoyGameDefinition;
import com.threerings.msoy.game.data.MsoyMatchConfig;
import com.threerings.msoy.game.xml.MsoyGameParser;

import com.threerings.msoy.item.data.ItemCodes;
import com.threerings.msoy.item.data.all.Game;
import com.threerings.msoy.item.data.all.MediaDesc;
import com.threerings.msoy.item.server.persist.GameDetailRecord;
import com.threerings.msoy.item.server.persist.GameRecord;
import com.threerings.msoy.item.server.persist.GameRepository;
import com.threerings.msoy.item.server.persist.ItemRecord;

import com.threerings.msoy.server.MsoyServer;
import com.threerings.msoy.server.ServerConfig;

import com.threerings.msoy.web.client.GameService;
import com.threerings.msoy.web.data.GameDetail;
import com.threerings.msoy.web.data.LaunchConfig;
import com.threerings.msoy.web.data.ServiceException;
import com.threerings.msoy.web.data.WebIdent;

import static com.threerings.msoy.Log.log;

/**
 * Provides the server implementation of {@link GameService}.
 */
public class GameServlet extends MsoyServiceServlet
    implements GameService
{
    // from interface GameService
    public LaunchConfig loadLaunchConfig (WebIdent ident, int gameId)
        throws ServiceException
    {
        // TODO: validate this user's ident

        // load up the metadata for this game
        GameRepository repo = MsoyServer.itemMan.getGameRepository();
        GameRecord grec;
        try {
            grec = repo.loadGameRecord(gameId);
            if (grec == null) {
                throw new ServiceException(ItemCodes.E_NO_SUCH_ITEM);
            }
        } catch (PersistenceException pe) {
            log.log(Level.WARNING, "Failed to load game record [gameId=" + gameId + "]", pe);
            throw new ServiceException(InvocationCodes.E_INTERNAL_ERROR);
        }
        final Game game = (Game)grec.toItem();

        // create a launch config record for the game
        LaunchConfig config = new LaunchConfig();
        config.gameId = game.gameId;

        MsoyMatchConfig match;
        try {
            if (StringUtil.isBlank(game.config)) {
                // fall back to a sensible default for our legacy games
                match = new MsoyMatchConfig();
                match.minSeats = match.startSeats = 1;
                match.maxSeats = 2;
            } else {
                MsoyGameDefinition def = (MsoyGameDefinition)new MsoyGameParser().parseGame(game);
                config.lwjgl = def.lwjgl;
                match = (MsoyMatchConfig)def.match;
            }

        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to parse XML game definition [id=" + gameId + "]", e);
            throw new ServiceException(InvocationCodes.INTERNAL_ERROR);
        }

        switch (game.gameMedia.mimeType) {
        case MediaDesc.APPLICATION_SHOCKWAVE_FLASH:
            config.type = game.isInWorld() ?
                    LaunchConfig.FLASH_IN_WORLD : LaunchConfig.FLASH_LOBBIED;
            break;
        case MediaDesc.APPLICATION_JAVA_ARCHIVE:
            // ignore maxSeats in the case of a party game - always display a lobby
            config.type = (!match.isPartyGame && match.maxSeats == 1) ?
                LaunchConfig.JAVA_SOLO : LaunchConfig.JAVA_FLASH_LOBBIED;
            break;
        default:
            log.warning("Requested config for game of unknown media type " +
                        "[id=" + gameId + ", media=" + game.gameMedia + "].");
            throw new ServiceException(InvocationCodes.E_INTERNAL_ERROR);
        }

        // we have to proxy game jar files through the game server due to the applet sandbox
        config.gameMediaPath = (game.gameMedia.mimeType == MediaDesc.APPLICATION_JAVA_ARCHIVE) ?
            game.gameMedia.getProxyMediaPath() : game.gameMedia.getMediaPath();
        config.name = game.name;
        config.server = ServerConfig.serverHost;
        config.port = ServerConfig.serverPorts[0];
        config.httpPort = ServerConfig.httpPort;
        return config;
    }

    // from interface GameService
    public GameDetail loadGameDetail (WebIdent ident, int gameId)
        throws ServiceException
    {
        GameRepository repo = MsoyServer.itemMan.getGameRepository();

        try {
            GameDetailRecord gdr = repo.loadGameDetail(gameId);
            if (gdr == null) {
                throw new ServiceException(ItemCodes.E_NO_SUCH_ITEM);
            }

            GameDetail detail = gdr.toGameDetail();
            if (gdr.listedItemId != 0) {
                ItemRecord item = repo.loadItem(gdr.listedItemId);
                if (item != null) {
                    detail.listedItem = (Game)item.toItem();
                }
            }
            if (gdr.sourceItemId != 0) {
                ItemRecord item = repo.loadItem(gdr.sourceItemId);
                if (item != null) {
                    detail.sourceItem = (Game)item.toItem();
                }
            }

            return detail;

        } catch (PersistenceException pe) {
            log.log(Level.WARNING, "Failed to load game detail [id=" + gameId + "].", pe);
            throw new ServiceException(InvocationCodes.INTERNAL_ERROR);
        }
    }
}
