//
// $Id$

package com.threerings.msoy.item.server.persist;

import com.samskivert.io.PersistenceException;
import com.samskivert.jdbc.ConnectionProvider;
import com.samskivert.jdbc.depot.annotation.Entity;
import com.samskivert.jdbc.depot.EntityMigration;

import com.threerings.toybox.server.ToyBoxManager;
import com.threerings.toybox.server.persist.Game;
import com.threerings.toybox.xml.GameParser;

import com.threerings.msoy.server.persist.TagHistoryRecord;
import com.threerings.msoy.server.persist.TagRecord;

import com.threerings.msoy.game.xml.MsoyGameParser;
import com.threerings.msoy.item.web.MediaDesc;

/**
 * Manages the persistent store of {@link Game} items.
 */
public class GameRepository extends ItemRepository<
        GameRecord,
        GameCloneRecord,
        GameCatalogRecord,
        GameRatingRecord>
    implements ToyBoxManager.GameRepository
{
    @Entity(name="GameTagRecord")
    public static class GameTagRecord extends TagRecord
    {
    }

    @Entity(name="GameTagHistoryRecord")
    public static class GameTagHistoryRecord extends TagHistoryRecord
    {
    }

    public GameRepository (ConnectionProvider provider)
    {
        super(provider);

        // these are old-funky version numbers
        _ctx.registerMigration(GameRecord.class,
            new EntityMigration.Drop((8 * 0x100) + 6, "desiredPlayers"));
        _ctx.registerMigration(GameRecord.class,
            new EntityMigration.Rename((8 * 0x100) + 6, "partyGameType", "gameType"));
    }

    // from ToyBoxManager.GameRepository
    public Game loadGame (int gameId)
        throws PersistenceException
    {
        // load up the GameRecord for this game and do some conversion
        GameRecord game = load(GameRecord.class, gameId);
        if (game == null) {
            return null;
        }

        // we create a custom parser that will create a custom game definition
        // that will look for the game jar file using the digest as a name
        // instead of the game ident
        Game tgame = new Game() {
            protected GameParser createParser () {
                return new MsoyGameParser();
            }
        };

        tgame.gameId = gameId;
        tgame.name = game.name;
        tgame.maintainerId = game.creatorId;
        tgame.status = Game.Status.READY.toString();
        tgame.host = ""; // TODO
        tgame.definition = game.config; 
        tgame.digest = MediaDesc.hashToString(game.gameMediaHash);
        tgame.description = ""; // TODO
        tgame.created = null; // TODO
        tgame.lastUpdated = null; // TODO
        return tgame;
    }

    // from ToyBoxManager.GameRepository
    public void incrementPlaytime (int gameId, int minutes)
        throws PersistenceException
    {
        // TODO: do we care?
    }

    // from ToyBoxManager.GameRepository
    public void updateOnlineCount (int gameId, int players)
        throws PersistenceException
    {
        // not used
    }

    @Override
    protected Class<GameRecord> getItemClass () {
        return GameRecord.class;
    }
    
    @Override
    protected Class<GameCatalogRecord> getCatalogClass ()
    {
        return GameCatalogRecord.class;
    }

    @Override
    protected Class<GameCloneRecord> getCloneClass ()
    {
        return GameCloneRecord.class;
    }
    
    @Override
    protected Class<GameRatingRecord> getRatingClass ()
    {
        return GameRatingRecord.class;
    }

    @Override
    protected TagRecord createTagRecord ()
    {
        return new GameTagRecord();
    }

    @Override
    protected TagHistoryRecord createTagHistoryRecord ()
    {
        return new GameTagHistoryRecord();
    }
}
