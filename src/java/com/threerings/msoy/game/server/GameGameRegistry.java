//
// $Id$

package com.threerings.msoy.game.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xml.sax.SAXException;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import com.samskivert.depot.DuplicateKeyException;
import com.samskivert.jdbc.RepositoryUnit;
import com.samskivert.jdbc.WriteOnlyUnit;
import com.samskivert.util.HashIntMap;
import com.samskivert.util.IntMap;
import com.samskivert.util.Interval;
import com.samskivert.util.Invoker;
import com.samskivert.util.StringUtil;

import com.threerings.presents.annotation.MainInvoker;
import com.threerings.presents.client.InvocationService;
import com.threerings.presents.data.ClientObject;
import com.threerings.presents.data.InvocationCodes;
import com.threerings.presents.dobj.RootDObjectManager;
import com.threerings.presents.server.InvocationException;
import com.threerings.presents.server.InvocationManager;
import com.threerings.presents.server.PresentsSession;
import com.threerings.presents.server.ShutdownManager;
import com.threerings.presents.util.PersistingUnit;
import com.threerings.presents.util.ResultListenerList;

import com.threerings.crowd.data.BodyObject;
import com.threerings.crowd.data.OccupantInfo;
import com.threerings.crowd.server.LocationManager;
import com.threerings.crowd.server.PlaceManager;
import com.threerings.crowd.server.PlaceManagerDelegate;
import com.threerings.crowd.server.PlaceRegistry;

import com.threerings.parlor.data.Table;
import com.threerings.parlor.game.data.GameConfig;
import com.threerings.parlor.rating.server.persist.RatingRepository;
import com.threerings.parlor.rating.util.Percentiler;
import com.threerings.parlor.server.ParlorSender;

import com.threerings.stats.data.Stat;

import com.threerings.bureau.server.BureauRegistry;

import com.whirled.bureau.data.BureauTypes;
import com.whirled.game.data.GameContentOwnership;
import com.whirled.game.data.GameData;
import com.whirled.game.server.PropertySpaceDelegate;

import com.threerings.msoy.data.MsoyCodes;
import com.threerings.msoy.data.StatType;
import com.threerings.msoy.data.UserAction;
import com.threerings.msoy.data.all.MediaDesc;
import com.threerings.msoy.server.BureauManager;
import com.threerings.msoy.server.MemberManager;
import com.threerings.msoy.server.MsoyEventLogger;
import com.threerings.msoy.server.StatLogic;
import com.threerings.msoy.server.persist.BatchInvoker;

import com.threerings.msoy.item.data.all.Game;
import com.threerings.msoy.item.data.all.Item;
import com.threerings.msoy.item.data.all.ItemPack;
import com.threerings.msoy.item.data.all.LevelPack;
import com.threerings.msoy.item.data.all.Prize;
import com.threerings.msoy.item.data.all.TrophySource;
import com.threerings.msoy.item.server.persist.GameRecord;
import com.threerings.msoy.item.server.persist.ItemPackRecord;
import com.threerings.msoy.item.server.persist.ItemPackRepository;
import com.threerings.msoy.item.server.persist.LevelPackRecord;
import com.threerings.msoy.item.server.persist.LevelPackRepository;
import com.threerings.msoy.item.server.persist.PrizeRecord;
import com.threerings.msoy.item.server.persist.PrizeRepository;
import com.threerings.msoy.item.server.persist.TrophySourceRecord;
import com.threerings.msoy.item.server.persist.TrophySourceRepository;

import com.threerings.msoy.admin.server.RuntimeConfig;
import com.threerings.msoy.money.server.MoneyLogic;
import com.threerings.msoy.money.server.MoneyNodeActions;
import com.threerings.msoy.person.server.persist.FeedRepository;
import com.threerings.msoy.person.util.FeedMessageType;

import com.threerings.msoy.avrg.client.AVRService;
import com.threerings.msoy.avrg.data.AVRGameConfig;
import com.threerings.msoy.avrg.server.AVRDispatcher;
import com.threerings.msoy.avrg.server.AVRGameManager;
import com.threerings.msoy.avrg.server.AVRProvider;
import com.threerings.msoy.avrg.server.QuestDelegate;
import com.threerings.msoy.avrg.server.persist.AVRGameRepository;
import com.threerings.msoy.avrg.server.persist.GameStateRecord;

import com.threerings.msoy.game.data.LobbyCodes;
import com.threerings.msoy.game.data.LobbyObject;
import com.threerings.msoy.game.data.MsoyGameCodes;
import com.threerings.msoy.game.data.MsoyGameConfig;
import com.threerings.msoy.game.data.MsoyGameDefinition;
import com.threerings.msoy.game.data.MsoyMatchConfig;
import com.threerings.msoy.game.data.PlayerObject;
import com.threerings.msoy.game.data.all.Trophy;
import com.threerings.msoy.game.server.persist.GameDetailRecord;
import com.threerings.msoy.game.server.persist.MsoyGameRepository;
import com.threerings.msoy.game.server.persist.TrophyRecord;
import com.threerings.msoy.game.server.persist.TrophyRepository;
import com.threerings.msoy.game.xml.MsoyGameParser;

import static com.threerings.msoy.Log.log;

/**
 * Manages the lobbies active on this server.
 */
@Singleton
public class GameGameRegistry
    implements ShutdownManager.Shutdowner, LobbyManager.ShutdownObserver,
               AVRGameManager.LifecycleObserver, LobbyProvider, AVRProvider, GameGameProvider
{
    /**
     * When updating metrics, there are these 3 modes.
     * @see GameGameRegistry#updateGameMetrics
     */
    public enum MetricType
    {
        SINGLE_PLAYER,
        MULTI_PLAYER,
        AVRG
    }

    @Inject public GameGameRegistry (ShutdownManager shutmgr, InvocationManager invmgr)
    {
        // register to hear when the server is shutdown
        shutmgr.registerShutdowner(this);

        // register game-related bootstrap services
        invmgr.registerDispatcher(new LobbyDispatcher(this), MsoyCodes.GAME_GROUP);
        invmgr.registerDispatcher(new AVRDispatcher(this), MsoyCodes.WORLD_GROUP);
        invmgr.registerDispatcher(new GameGameDispatcher(this), MsoyCodes.GAME_GROUP);
    }

    /**
     * Provides this registry with an injector it can use to create manager instances.
     */
    public void init (Injector injector)
    {
        _injector = injector;

        // periodically purge old game logs
        new Interval(_invoker) {
            @Override public void expired () {
                _mgameRepo.purgeTraceLogs();
            }
        }.schedule(LOG_DELETION_INTERVAL, LOG_DELETION_INTERVAL);
    }

    /**
     * Returns an enumeration of all of the registered lobby managers.  This should only be
     * accessed on the dobjmgr thread and shouldn't be kept around across event dispatches.
     */
    public Iterator<LobbyManager> enumerateLobbyManagers ()
    {
        return _lobbies.values().iterator();
    }

    /**
     * Returns the percentiler for the specified game and score distribution. The percentiler may
     * be modified and when the lobby for the game in question is finally unloaded, the percentiler
     * will be written back out to the database.
     */
    public Percentiler getScoreDistribution (int gameId, boolean multiplayer, int gameMode)
    {
        if (gameMode < 0 || gameMode >= MAX_GAME_MODES) {
            log.warning("Requested invalid score distribution", "gameId", gameId, "mode", gameMode);
            gameMode = 0;
        }
        log.debug("Attempting to locate score distribution", "gameId", gameId, "mp", multiplayer,
                  "gameMode", gameMode);
        TilerKey key = new TilerKey(gameId, multiplayer, gameMode);
        Percentiler tiler = _distribs.get(key);
        if (tiler == null) {
            // a game wants to register a score for a previously unseen mode; return a fresh tiler
            _distribs.put(key, tiler = new Percentiler());
        }
        return tiler;
    }

    /**
     * Called to inform us that our game's content (the GameRecord or any of our sub-items) has
     * been updated. We reload the game's content and inform the lobby so that newly created games
     * will use the new content.
     */
    public void gameContentUpdated (final int gameId)
    {
        // is this a lobbied game?
        final LobbyManager lmgr = _lobbies.get(gameId);
        if (lmgr != null) {
            _invoker.postUnit(new RepositoryUnit("reloadLobby") {
                @Override
                public void invokePersist () throws Exception {
                    // if so, recompile the game content from all its various sources
                    _content = assembleGameContent(gameId);
                }

                @Override
                public void handleSuccess () {
                    // and then update the lobby with the content
                    lmgr.setGameContent(_content);
                    _gameContent.put(gameId, _content);
                    log.info("Reloaded lobbied game configuration [id=" + gameId + "]");
                }

                @Override
                public void handleFailure (Exception e) {
                    // if anything goes wrong, we can just fall back on what was already there
                    log.warning("Failed to resolve game [id=" + gameId + "].", e);
                }

                protected GameContent _content;
            });

            return;
        }

        // see if it's a lobby game we're currently loading...
        ResultListenerList list = _loadingLobbies.get(gameId);
        if (list != null) {
            list.add(new InvocationService.ResultListener() {
                public void requestProcessed (Object result) {
                    gameContentUpdated(gameId);
                }
                public void requestFailed (String cause) {/*ignore*/}
            });
            return;
        }

        // else is it an AVRG?
        final AVRGameManager amgr = _avrgManagers.get(gameId);
        if (amgr != null) {
            forciblyShutdownAVRG(amgr, "content updated");
            return;
        }

        log.warning("Updated game record not, in the end, hosted by us [gameId=" + gameId + "]");
    }

    /**
     * Called when the player has purchased new game content.
     */
    public void gameContentPurchased (int playerId, int gameId, byte itemType, String ident)
    {
        PlayerObject player = _locator.lookupPlayer(playerId);
        if (player == null) {
            return; // not online or not in a game, no problem!
        }

        // convert the item type to a GameData content type
        byte contentType;
        if (itemType == Item.LEVEL_PACK) {
            contentType = GameData.LEVEL_DATA;
        } else if (itemType == Item.ITEM_PACK) {
            contentType = GameData.ITEM_DATA;
        } else {
            log.warning("Notified that player purchased content of unknown type",
                        "playerId", playerId, "gameId", gameId, "itemType", itemType,
                        "ident", ident);
            return;
        }

        // make sure they're actually playing the right game
        PlaceManager plmgr = _placeReg.getPlaceManager(player.getPlaceOid());
        if (plmgr != null && plmgr.getConfig() instanceof MsoyGameConfig &&
            ((MsoyGameConfig)plmgr.getConfig()).getGameId() == gameId) {
            GameContentOwnership entry = new GameContentOwnership(gameId, contentType, ident);
            if (!player.gameContent.contains(entry)) {
                player.addToGameContent(entry);
            }
        }
    }

    /**
     * Resets our in-memory percentiler for the specified game and game mode.
     * This is triggered by a request from our world server.
     */
    public void resetScorePercentiler (int gameId, boolean single, int gameMode)
    {
        log.info("Resetting in-memory percentiler", "gameId", gameId, "single", single,
                 "gameMode", gameMode);
        _distribs.remove(new TilerKey(gameId, !single, gameMode));
    }

    /**
     * Reports the supplied incremental coin award to a member's runtime but does not actually
     * award the coins. The real coin award will come once when the player finally leaves the game.
     */
    public void reportCoinAward (int memberId, int deltaCoins)
    {
        _moneyActions.coinsEarned(memberId, deltaCoins);
    }

    /**
     * Makes the supplied coin award to the player identified by the supplied user action.
     */
    public void awardCoins (final int gameId, final UserAction action, final int coinAward)
    {
        _invoker.postUnit(new WriteOnlyUnit("awardCoins(" + gameId + ")") {
            public void invokePersist () throws Exception {
                _moneyLogic.awardCoins(action.memberId, coinAward, false, action);
            }
        });
    }

    /**
     * Called when a game was successfully finished with a payout.  Right now just logs the results
     * for posterity.
     */
    public void gameDidPayout (int memberId, Game game, int payout, int secondsPlayed)
    {
        _eventLog.gamePlayed(game.genre, game.gameId, game.itemId, payout, secondsPlayed, memberId);
    }

    /**
     * Awards the supplied trophy and provides a {@link Trophy} instance on success to the supplied
     * listener or failure.
     */
    public void awardTrophy (final String gameName, final TrophyRecord trophy, String description,
                             InvocationService.ResultListener listener)
    {
        final Trophy trec = trophy.toTrophy();
        trec.description = description;

        _invoker.postUnit(new PersistingUnit("awardTrophy", listener) {
            @Override
            public void invokePersistent () throws Exception {
                try {
                    _trophyRepo.storeTrophy(trophy); // store the trophy in the database
                } catch (DuplicateKeyException dke) {
                    throw new InvocationException("e.trophy_already_awarded");
                }

                if (!Game.isDevelopmentVersion(trophy.gameId)) {
                    // publish the trophy earning event to the member's feed
                    _feedRepo.publishMemberMessage(
                        trophy.memberId, FeedMessageType.FRIEND_WON_TROPHY,
                        trophy.name + "\t" + trophy.gameId +
                        "\t" + MediaDesc.mdToString(trec.trophyMedia));

                    // report the trophy award to panopticon
                    _eventLog.trophyEarned(trophy.memberId, trophy.gameId, trophy.ident);

                    // increment their trophies earned stat
                    _statLogic.incrementStat(trophy.memberId, StatType.TROPHIES_EARNED, 1);
                }
            }
            @Override public void handleSuccess () {
                reportRequestProcessed(trec);
            }
            @Override protected String getFailureMessage () {
                return "Failed to store trophy " + trophy + ".";
            }
        });
    }

    public void incrementStat (final int memberId, final Stat.Type type, final int delta)
    {
        _invoker.postUnit(new WriteOnlyUnit("incrementStat(" + memberId + ")") {
            public void invokePersist () throws Exception {
                _statLogic.incrementStat(memberId, type, delta);
            }
        });
    }

    public void addToSetStat (final int memberId, final Stat.Type type, final int value)
    {
        _invoker.postUnit(new WriteOnlyUnit("incrementStat(" + memberId + ")") {
            public void invokePersist () throws Exception {
                _statLogic.addToSetStat(memberId, type, value);
            }
        });
    }

    /**
     * Notes that some number of games were played, player minutes accumulated and coins paid out
     * for the supplied game. The appropriate records are stored in the database to faciliate
     * payout factor recalculation, recalculation is performed if the time to do so has arrived and
     * the supplied detail record is updated with the new information. Separate data is kept for
     * single player and multiplayer game plays. AVR games are always stored as multiplayer, but
     * use a different payout rate.
     */
    public void updateGameMetrics (final GameDetailRecord detail, MetricType type,
                                   int minutesPlayed, final int gamesPlayed, final int coinsAwarded)
    {
        // update our in-memory record to reflect this gameplay
        detail.flowToNextRecalc -= coinsAwarded;
        detail.gamesPlayed += gamesPlayed;

        // determine whether or not it's time to recalculate this game's payout factor
        final int hourlyRate = type == MetricType.AVRG ?
            _runtime.money.hourlyAVRGameFlowRate :
            _runtime.money.hourlyGameFlowRate;
        final boolean isMultiplayer = type != MetricType.SINGLE_PLAYER;
        final int newFlowToNextRecalc;
        if (detail.flowToNextRecalc <= 0) {
            newFlowToNextRecalc = _runtime.money.payoutFactorReassessment * hourlyRate +
                detail.flowToNextRecalc;
            detail.flowToNextRecalc = newFlowToNextRecalc;
        } else {
            newFlowToNextRecalc = 0;
        }

        // record this gameplay for future game metrics tracking and blah blah
        final int gameId = detail.gameId, playerMins = Math.max(minutesPlayed, 1);
        _batchInvoker.postUnit(new RepositoryUnit("updateGameDetail(" + gameId + ")") {
            @Override public void invokePersist () throws Exception {
                // note that game were played
                _mgameRepo.noteGamePlayed(
                    gameId, isMultiplayer, gamesPlayed, playerMins, coinsAwarded);
                // if it's time to recalc our payout factor, do that
                if (newFlowToNextRecalc > 0) {
                    _newData = _mgameRepo.computeAndUpdatePayoutFactor(
                        gameId, newFlowToNextRecalc, hourlyRate);
                }
            }
            @Override public void handleSuccess () {
                // update the in-memory detail record if we changed things
                if (_newData != null) {
                    detail.payoutFactor = _newData[0];
                    detail.avgSingleDuration = _newData[1];
                    detail.avgMultiDuration = _newData[2];
                }
            }
            @Override // from Invoker.Unit
            public long getLongThreshold () {
                return 10 * 1000;
            }
            protected int[] _newData;
        });
    }

    /**
     * Attempts to flush any pending coin earnings for the specified player.
     */
    public void flushCoinEarnings (final int playerId)
    {
        PlayerObject plobj = _locator.lookupPlayer(playerId);
        if (plobj == null) {
            return; // not online or not in a game, no problem!
        }

        PlaceManager plmgr = _placeReg.getPlaceManager(plobj.getPlaceOid());
        if (plmgr != null) {
            // this will NOOP if their place manager has no AwardDelegate
            plmgr.applyToDelegates(new PlaceManager.DelegateOp(AwardDelegate.class) {
                public void apply (PlaceManagerDelegate delegate) {
                    ((AwardDelegate)delegate).flushCoinEarnings(playerId);
                }
            });
            plmgr.applyToDelegates(new PlaceManager.DelegateOp(QuestDelegate.class) {
                public void apply (PlaceManagerDelegate delegate) {
                    ((QuestDelegate)delegate).flushCoinEarnings(playerId);
                }
            });
        }
    }

    // from interface PresentsServer.Shutdowner
    public void shutdown ()
    {
        // shutdown our active lobbies
        for (LobbyManager lmgr : _lobbies.values().toArray(new LobbyManager[_lobbies.size()])) {
            lobbyDidShutdown(lmgr.getGame().gameId);
        }

        for (AVRGameManager amgr : _avrgManagers.values()) {
            amgr.shutdown(); // this will also call avrGameDidShutdown
        }
    }

    // from interface LobbyManager.ShutdownObserver
    public void lobbyDidShutdown (int gameId)
    {
        log.info("Shutting down lobby", "gameId", gameId);

        // destroy our record of that lobby
        _lobbies.remove(gameId);
        _loadingLobbies.remove(gameId); // just in case
        _gameContent.remove(gameId);

        // kill the bureau session, if any
        killBureauSession(gameId);

        // let our world server know we're audi
        _wgameReg.clearGame(gameId);

        // flush any modified percentile distributions
        flushPercentilers(gameId);
    }

    // from AVRGameManager.LifecycleObserver
    public void avrGameDidShutdown (AVRGameManager mgr)
    {
        final int gameId = mgr.getGameId();

        // destroy our record of that avrg
        _avrgManagers.remove(gameId);
        _loadingAVRGames.remove(gameId);
        _gameContent.remove(gameId);

        // kill the bureau session in 30 seconds if the game has not started back up so that the
        // agent has plenty of time to finish stopping
        new Interval(_omgr) {
            @Override public void expired () {
                if (!_avrgManagers.containsKey(gameId)) {
                    killBureauSession(gameId);
                }
            }
        }.schedule(30 * 1000);

        // let our world server know we're audi
        _wgameReg.clearGame(gameId);
    }

    // from AVRGameManager.LifecycleObserver
    public void avrGameReady (AVRGameManager mgr)
    {
        int gameId = mgr.getGameId();

        _avrgManagers.put(gameId, mgr);

        ResultListenerList list = _loadingAVRGames.remove(gameId);
        if (list != null) {
            list.requestProcessed(mgr);
        } else {
            log.warning("No listeners when done activating AVRGame", "gameId", gameId);
        }
    }

    // from AVRGameManager.LifecycleObserver
    public void avrGameAgentFailedToStart (AVRGameManager mgr, Exception error)
    {
        int gameId = mgr.getGameId();

        if (_avrgManagers.get(gameId) != null) {
            log.warning(
                "Agent failed to start but is already started?", "gameId", gameId);
        }

        ResultListenerList list = _loadingAVRGames.remove(gameId);
        if (list != null) {
            // If the launcher wasn't connected, this is our bad, tell the user to try again later
            // otherwise, report a generic agent failure message.
            if (error != null && error instanceof BureauManager.LauncherNotConnected) {
                list.requestFailed("e.game_server_not_ready");
            } else {
                list.requestFailed("e.agent_error");
            }
        } else {
            log.warning(
                "No listeners when AVRGame agent failed", "gameId", gameId);
        }

        mgr.shutdown();
    }

    // from AVRGameManager.LifecycleObserver
    public void avrGameAgentDestroyed (AVRGameManager mgr)
    {
        // we don't like to run AVRGs with no agents, shut 'er down
        forciblyShutdownAVRG(mgr, "agent destroyed");
    }

    // from AVRProvider
    public void activateGame (ClientObject caller, final int gameId,
                              AVRService.AVRGameJoinListener listener)
        throws InvocationException
    {
        // TODO: transition
        // int instanceId = 1;

        PlayerObject player = (PlayerObject) caller;

        InvocationService.ResultListener joinListener =
            new AVRGameJoinListener(player.getMemberId(), listener);

        ResultListenerList list = _loadingAVRGames.get(gameId);
        if (list != null) {
            list.add(joinListener);
            return;
        }

        AVRGameManager mgr = _avrgManagers.get(gameId);
        if (mgr != null) {
            joinListener.requestProcessed(mgr);
            return;
        }

        _loadingAVRGames.put(gameId, list = new ResultListenerList());
        list.add(joinListener);

        _invoker.postUnit(new RepositoryUnit("activateAVRGame") {
            @Override
            public void invokePersist () throws Exception {
                _content = assembleGameContent(gameId);
                if (_content.game != null) {
                    _recs = _avrgRepo.getGameState(gameId);
                }
            }

            @Override
            public void handleSuccess () {
                if (_content.game == null) {
                    log.warning("Content has no game", "gameId", gameId);
                    reportFailure(MsoyGameCodes.E_NO_SUCH_GAME);
                    return;
                }

                MsoyGameDefinition def;
                try {
                    def = (MsoyGameDefinition)new MsoyGameParser().parseGame(_content.game);

                } catch (IOException ioe) {
                    log.warning("Error parsing game config", "game", _content.game, ioe);
                    reportFailure(MsoyGameCodes.E_BAD_GAME_CONTENT);
                    return;

                } catch (SAXException saxe) {
                    log.warning("Error parsing game config", "game", _content.game, saxe);
                    reportFailure(MsoyGameCodes.E_BAD_GAME_CONTENT);
                    return;

                }

                if (StringUtil.isBlank(def.getServerMediaPath(gameId))) {
                    log.info("AVRG missing server agent code", "gameId", gameId);
                    reportFailure(MsoyGameCodes.E_BAD_GAME_CONTENT);
                    return;
                }

                log.info("Setting up AVRG manager", "game", _content.game);

                List<PlaceManagerDelegate> delegates = Lists.newArrayList();
                // TODO: Move AVRG event logging out of QuestDelegate and maybe into this one?
//                delegates.add(new EventLoggingDelegate(_content));
                // TODO: Refactor the bits of AwardDelegate that we want
//                delegates.add(new AwardDelegate(_content));
                delegates.add(new TrophyDelegate(_content));
                delegates.add(new QuestDelegate(_content));
                delegates.add(new TrackExperienceDelegate(_content));

                int minLogInterval, maxLogInterval;
                if (Game.isDevelopmentVersion(gameId)) {
                    // write dev logs at least every two minutes but at most one per minute
                    minLogInterval = 1;
                    maxLogInterval = 2;
                } else {
                    // write listed logs at least every 2 hours, but at most one per 20 minutes
                    minLogInterval = 20;
                    maxLogInterval = 120;
                }

                delegates.add(new AgentTraceDelegate(gameId, minLogInterval, maxLogInterval));

                final Map<String, byte[]> initialState = new HashMap<String, byte[]>();
                for (GameStateRecord record : _recs) {
                    initialState.put(record.datumKey, record.datumValue);
                }
                delegates.add(new PropertySpaceDelegate() {
                    @Override
                    protected Map<String, byte[]> initialStateFromStore () {
                        return initialState;
                    }
                    @Override
                    protected void writeDirtyStateToStore (final Map<String, byte[]> state) {
                        // the map should be quite safe to pass to another thread
                        _invoker.postUnit(new WriteOnlyUnit("shutdown") {
                            @Override
                            public void invokePersist () throws Exception {
                                for (Map.Entry<String, byte[]> entry : state.entrySet()) {
                                    _avrgRepo.storeState(new GameStateRecord(
                                        gameId, entry.getKey(), entry.getValue()));
                                }
                            }
                        });
                    }
                });

                AVRGameConfig config = new AVRGameConfig();
                config.init(_content.game, def);

                AVRGameManager mgr;
                try {
                    mgr = (AVRGameManager)_placeReg.createPlace(config, delegates);
                    _gameContent.put(gameId, _content);

                } catch (Exception e) {
                    log.warning("Failed to create AVRGameObject", "gameId", gameId, e);
                    reportFailure(MsoyGameCodes.E_INTERNAL_ERROR);
                    return;
                }

// TODO: now handled in MsoyGameDefinition
//                mgr.getGameObject().setGameMedia(_content.game.gameMedia);
                mgr.setLifecycleObserver(GameGameRegistry.this);

                // now start up the agent, then wait for the avrGameReady callback
                mgr.startAgent();
                // TODO: add a timeout?
            }

            @Override
            public void handleFailure (Exception pe) {
                log.warning("Failed to resolve AVRGame [id=" + gameId + "].", pe);
                reportFailure(pe.getMessage());
            }

            protected void reportFailure (String reason) {
                ResultListenerList list = _loadingAVRGames.remove(gameId);
                if (list != null) {
                    list.requestFailed(reason);
                } else {
                    log.warning(
                        "No listeners when failing AVRGame [gameId=" + gameId + "]");
                }
            }

            protected GameContent _content;
            protected List<GameStateRecord> _recs;
        });
    }

    // from AVRProvider
    public void deactivateGame (ClientObject caller, int gameId,
                                InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        PlayerObject player = (PlayerObject) caller;
        int playerId = player.getMemberId();

        // see if we are still just resolving the game
        ResultListenerList list = _loadingAVRGames.get(gameId);
        if (list != null) {
            // yep, so just remove our associated listener, and we're done
            for (InvocationService.ResultListener gameListener : list) {
                if (((AVRGameJoinListener) gameListener).getPlayerId() == playerId) {
                    list.remove(gameListener);
                    listener.requestProcessed();
                    return;
                }
            }
        }

        // get the corresponding manager
        AVRGameManager mgr = _avrgManagers.get(gameId);
        if (mgr != null) {
            _locmgr.leavePlace(player);

            // Make sure we notify the world server too, since we are officially deactivating this
            // game as opposed to just leaving it tempoararily.
            _playerActions.leaveAVRGame(playerId);

        } else {
            log.warning("Tried to deactivate AVRG without manager [gameId=" + gameId + "]");
        }

        listener.requestProcessed();
    }

    // from LobbyProvider
    public void identifyLobby (ClientObject caller, final int gameId,
                               InvocationService.ResultListener listener)
        throws InvocationException
    {
        // if we're already resolving this lobby, add this listener to the list of those interested
        // in the outcome
        ResultListenerList list = _loadingLobbies.get(gameId);
        if (list != null) {
            list.add(listener);
            return;
        }

        // if the lobby is already resolved, we're good
        LobbyManager mgr = _lobbies.get(gameId);
        if (mgr != null) {
            listener.requestProcessed(mgr.getLobbyObject().getOid());
            return;
        }

        // otherwise we need to do the resolving
        _loadingLobbies.put(gameId, list = new ResultListenerList());
        list.add(listener);

        _invoker.postUnit(new RepositoryUnit("loadLobby") {
            @Override
            public void invokePersist () throws Exception {
                _content = assembleGameContent(gameId);
                // load up the score distribution information for this game as well
                _singles = _ratingRepo.loadPercentiles(-Math.abs(gameId));
                _multis = _ratingRepo.loadPercentiles(Math.abs(gameId));
            }

            @Override
            public void handleSuccess () {
                if (_content.game == null) {
                    reportFailure("m.no_such_game");
                    return;
                }

                LobbyManager lmgr = _injector.getInstance(LobbyManager.class);
                lmgr.init(_invmgr, GameGameRegistry.this);
                lmgr.setGameContent(_content);
                _gameContent.put(gameId, _content);
                _lobbies.put(gameId, lmgr);

                ResultListenerList list = _loadingLobbies.remove(gameId);
                if (list != null) {
                    list.requestProcessed(lmgr.getLobbyObject().getOid());
                }

                // map this game's score distributions
                for (Map.Entry<Integer, Percentiler> entry : _singles.entrySet()) {
                    _distribs.put(new TilerKey(gameId, false, entry.getKey()), entry.getValue());
                }
                for (Map.Entry<Integer, Percentiler> entry : _multis.entrySet()) {
                    _distribs.put(new TilerKey(gameId, true, entry.getKey()), entry.getValue());
                }
            }

            @Override
            public void handleFailure (Exception pe) {
                log.warning("Failed to resolve game [id=" + gameId + "].", pe);
                reportFailure(InvocationCodes.E_INTERNAL_ERROR);
            }

            protected void reportFailure (String reason) {
                ResultListenerList list = _loadingLobbies.remove(gameId);
                if (list != null) {
                    list.requestFailed(reason);
                }

                // clear out the hosting record that our world server assigned to us when it sent
                // this client our way to resolve this game
                _wgameReg.clearGame(gameId);
            }

            protected GameContent _content;
            protected Map<Integer, Percentiler> _singles, _multis;
        });
    }

    // from LobbyProvider
    public void playNow (ClientObject caller, final int gameId, int playerId,
                         final InvocationService.ResultListener listener)
        throws InvocationException
    {
        final PlayerObject plobj = (PlayerObject)caller;

        // if they want to join a player's game, try that first
        if (playerId != 0) {
            int gameOid = locatePlayerGame(playerId);
            if (gameOid != 0) {
                // deliver a game ready notification to the player
                ParlorSender.gameIsReady(plobj, gameOid);
                listener.requestProcessed(0);
                return;
            } // otherwise fall through and send them to the lobby or a game
        }

        // we need to make sure the lobby is resolved, so route through identifyLobby
        identifyLobby(caller, gameId, new InvocationService.ResultListener() {
            public void requestProcessed (Object result) {
                LobbyManager mgr = _lobbies.get(gameId);
                if (mgr == null) {
                    log.warning("identifyLobby returned non-failure but lobby manager disappeared",
                                "gameId", gameId);
                    requestFailed(InvocationCodes.E_INTERNAL_ERROR);
                } else if (mgr.playNowSingle(plobj)) {
                    listener.requestProcessed(0); // we'll send gameIsReady shortly
                } else {
                    listener.requestProcessed((Integer)result); // send back the lobbyOid
                }
            }
            public void requestFailed (String cause) {
                listener.requestFailed(cause);
            }
        });
    }

    protected int locatePlayerGame (int playerId)
    {
        PlayerObject player = _locator.lookupPlayer(playerId);
        if (player == null) {
            return 0;
        }

        int placeOid = player.getPlaceOid();
        if (placeOid == -1) {
            return 0;
        }

        PlaceManager plman = _placeReg.getPlaceManager(placeOid);
        if (plman == null) {
            log.warning("No PlaceManager for player's game?", "pid", playerId, "ploid", placeOid);
            return 0;
        }

        // if this is an AVRG, we should be doing something different on the client
        if (!(plman instanceof MsoyGameManager)) {
            log.warning("Requested to join player that's probably in an AVRG ", "pid", playerId,
                        "mgr", plman.getClass().getName());
            return 0;
        }

        // check to make sure the game that they're in is watchable
        MsoyGameConfig gameConfig = (MsoyGameConfig) plman.getConfig();
        MsoyMatchConfig matchConfig = (MsoyMatchConfig) gameConfig.getGameDefinition().match;
        if (matchConfig.unwatchable) {
            return 0;
        }

        // check to make sure the game that they're in is not private
        int gameId = gameConfig.getGameId();
        LobbyManager lmgr = _lobbies.get(gameId);
        if (lmgr == null) {
            log.warning("No lobby manager found for existing game! [" + gameId + "]");
            return 0;
        }
        LobbyObject lobj = lmgr.getLobbyObject();
        for (Table table : lobj.tables) {
            if (table.gameOid == placeOid) {
                if (table.tconfig.privateTable) {
                    return 0;
                }
                break;
            }
        }

        // finally, hand off the game oid
        return placeOid;
    }

    // from LobbyProvider
    public void joinPlayerGame (ClientObject caller, int playerId,
                                InvocationService.ResultListener listener)
        throws InvocationException
    {
    }

    // from interface GameGameProvider
    public void getTrophies (ClientObject caller, int gameId, InvocationService.ResultListener lner)
        throws InvocationException
    {
        final PlayerObject plobj = (PlayerObject)caller;

        GameContent content = _gameContent.get(gameId);
        if (content == null) {
            log.warning("Requested trophies before manager was fully resolved?", "id", gameId,
                "lmgr", _lobbies.get(gameId), "amgr", _avrgManagers.get(gameId));
            throw new InvocationException(InvocationCodes.E_INTERNAL_ERROR);
        }

        // TODO: load up trophy earned timestamps so that we can fill them in instead of just
        // knowing if a trophy is earned or not; alas our database free method will be sullied

        final int fGameId = gameId;
        List<Trophy> trophies = Lists.transform(
            content.tsources, new Function<TrophySource, Trophy>() {
                public Trophy apply (TrophySource source) {
                    Trophy trophy = new Trophy();
                    trophy.gameId = fGameId;
                    trophy.trophyMedia = source.getPrimaryMedia();
                    trophy.name = source.name;
                    boolean got = plobj.ownsGameContent(
                        fGameId, GameData.TROPHY_DATA, source.ident);
                    if (!source.secret || got) {
                        trophy.description = source.description;
                    }
                    if (got) {
                        trophy.whenEarned = System.currentTimeMillis(); // TODO
                    }
                    return trophy;
                }
            });

        lner.requestProcessed(trophies.toArray(new Trophy[trophies.size()]));
    }

    // from interface GameGameProvider
    public void complainPlayer (ClientObject caller, final int memberId, String complaint)
    {
        PlayerObject target = _locator.lookupPlayer(memberId);
        _memmgr.complainMember((BodyObject) caller, memberId,
            complaint, (target != null) ? target.getMemberName() : null);
    }

    // from interface GameGameProvider
    public void removeDevelopmentTrophies (ClientObject caller, final int gameId,
                                           InvocationService.ConfirmListener lner)
        throws InvocationException
    {
        final PlayerObject plobj = (PlayerObject)caller;
        if (plobj.getMemberName().isGuest()) {
            log.warning("Requested to remove development trophies from a guest", "gameId", gameId);
            throw new InvocationException(InvocationCodes.E_INTERNAL_ERROR);
        }

        _invoker.postUnit(new PersistingUnit("removeDevelopmentTrophies", lner) {
            @Override public void invokePersistent() throws Exception {
                _trophies = _trophyRepo.loadTrophies(gameId, plobj.getMemberId());
                _trophyRepo.removeDevelopmentTrophies(gameId, plobj.getMemberId());
            }

            @Override public void handleSuccess() {
                plobj.startTransaction();
                try {
                    for (TrophyRecord trec : _trophies) {
                        plobj.removeFromGameContent(new GameContentOwnership(
                            gameId, GameData.TROPHY_DATA, trec.ident));
                    }
                } finally {
                    plobj.commitTransaction();
                }
                reportRequestProcessed();
            }

            @Override protected String getFailureMessage () {
                return "Failed to remove in-development trophies [game=" + gameId +
                    ", who=" + plobj.who() + "].";
            }

            protected List<TrophyRecord> _trophies;
        });
    }

    protected GameContent assembleGameContent (int gameId)
    {
        GameContent content = new GameContent();
        content.detail = _mgameRepo.loadGameDetail(gameId);
        GameRecord rec = _mgameRepo.loadGameRecord(gameId, content.detail);
        if (rec != null) {
            content.game = (Game)rec.toItem();
            int suiteId = content.game.getSuiteId();
            // load up our level and item packs
            for (LevelPackRecord record : _lpackRepo.loadOriginalItemsBySuite(suiteId)) {
                content.lpacks.add((LevelPack)record.toItem());
            }
            for (ItemPackRecord record :
                     _ipackRepo.loadOriginalItemsBySuite(content.game.getSuiteId())) {
                content.ipacks.add((ItemPack)record.toItem());
            }

            // load up our trophy source items
            Set<TrophySourceRecord> tsrecs = Sets.newTreeSet(TrophySourceRecord.BY_SORT_ORDER);
            tsrecs.addAll(_tsourceRepo.loadOriginalItemsBySuite(suiteId));
            List<String> debugIdents = Lists.newArrayList();
            for (TrophySourceRecord record : tsrecs) {
                content.tsources.add((TrophySource)record.toItem());
                debugIdents.add(record.ident);
            }
            log.info("Loaded trophy sources for game", "game", gameId, "trophies", debugIdents);

            // load up our prize items
            for (PrizeRecord record : _prizeRepo.loadOriginalItemsBySuite(suiteId)) {
                content.prizes.add((Prize)record.toItem());
            }
        }
        return content;
    }

    protected void flushPercentilers (int gameId)
    {
        final Map<TilerKey, Percentiler> toFlush = Maps.newHashMap();
        for (boolean multiplayer : new Boolean[] { true, false }) {
            for (int mode = 0; mode < MAX_GAME_MODES; mode++) {
                TilerKey key = new TilerKey(gameId, multiplayer, mode);
                final Percentiler tiler = _distribs.remove(key);
                if (tiler == null || !tiler.isModified()) {
                    continue;
                }
                log.info("Marking percentiler as needing a flush", "key", key);
                toFlush.put(key, tiler);
            }
        }

        if (toFlush.size() > 0) {
            _invoker.postUnit(new Invoker.Unit("flushPercentiler") {
                @Override public boolean invoke () {
                    for (Map.Entry<TilerKey, Percentiler> entry : toFlush.entrySet()) {
                        TilerKey key = entry.getKey();
                        int gameId = key.multiplayer ? key.gameId : -key.gameId;
                        try {
                            log.info("Actually updating percentiler", "key", key);
                            _ratingRepo.updatePercentile(gameId, key.gameMode, entry.getValue());

                        } catch (Exception e) {
                            log.warning("Failed to update score distribution", "key", key,
                                        "tiler", entry.getValue(), e);
                        }
                    }
                    return false;
                }
            });
        }
    }

    /**
     * Evict all the players in an avrg then shut it down.
     */
    protected void forciblyShutdownAVRG (AVRGameManager amgr, String why)
    {
        // copy the occupant set as a player list, as occupancy is modified in the loop below
        List<PlayerObject> players = Lists.newArrayList();
        for (OccupantInfo playerInfo : amgr.getGameObject().occupantInfo) {
            PlayerObject player = (PlayerObject) _omgr.getObject(playerInfo.bodyOid);
            if (player != null) {
                players.add(player);
            }
        }

        log.info("AVRG " + why + ": evicting players and shutting down manager",
                 "gameId", amgr.getGameId(), "evicted", players.size());

        // now throw the players out
        for (PlayerObject player : players) {
            _locmgr.leavePlace(player);
        }

        // then immediately shut down the manager
        amgr.shutdown();
    }

    protected void killBureauSession (int gameId)
    {
        String bureauId = BureauTypes.GAME_BUREAU_ID_PREFIX + gameId;
        PresentsSession bureau = _bureauReg.lookupClient(bureauId);
        if (bureau != null) {
            bureau.endSession();
        }
    }

    protected class AVRGameJoinListener
        implements InvocationService.ResultListener
    {
        protected AVRGameJoinListener (int player, AVRService.AVRGameJoinListener listener)
        {
            _listener = listener;
            _player = player;
        }

        public int getPlayerId ()
        {
            return _player;
        }

        public void requestProcessed (Object result) {
            ((AVRGameManager) result).joinGame(_player, _listener);
        }

        public void requestFailed (String cause) {
            _listener.requestFailed(cause);
        }

        protected int _player;
        protected AVRService.AVRGameJoinListener _listener;
    }

    protected static class TilerKey
    {
        public final int gameId;
        public final boolean multiplayer;
        public final int gameMode;

        public TilerKey (int gameId, boolean multiplayer, int gameMode) {
            this.gameId = Math.abs(gameId);
            this.multiplayer = multiplayer;
            this.gameMode = gameMode;
        }

        @Override public int hashCode () {
            return gameId ^ (multiplayer ? 1 : 0) ^ gameMode;
        }

        @Override public boolean equals (Object other) {
            TilerKey okey = (TilerKey)other;
            return gameId == okey.gameId && multiplayer == okey.multiplayer &&
                gameMode == okey.gameMode;
        }

        public String toString ()
        {
            return "TilerKey " +
                "(gameId=" + gameId + ", mp=" + multiplayer + ", mode=" + gameMode + ")";
        }
    }

    /** Maps game id -> lobby. */
    protected IntMap<LobbyManager> _lobbies = new HashIntMap<LobbyManager>();

    /** Maps game id -> a mapping of various percentile distributions. */
    protected Map<TilerKey, Percentiler> _distribs = Maps.newHashMap();

    /** Maps game id -> listeners waiting for a lobby to load. */
    protected IntMap<ResultListenerList> _loadingLobbies = new HashIntMap<ResultListenerList>();

    /** Maps game id -> manager for AVR games. */
    protected IntMap<AVRGameManager> _avrgManagers = new HashIntMap<AVRGameManager>();

    /** Maps game id -> listeners waiting for a lobby to load. */
    protected IntMap<ResultListenerList> _loadingAVRGames = new HashIntMap<ResultListenerList>();

    /** We use this to inject dependencies into managers we create. */
    protected Injector _injector;

    /** Content objects for all games (lobbied or avrg). */
    protected IntMap<GameContent> _gameContent = new HashIntMap<GameContent>();

    // various and sundry dependent services
    @Inject protected @MainInvoker Invoker _invoker;
    @Inject protected @BatchInvoker Invoker _batchInvoker;
    @Inject protected MsoyEventLogger _eventLog;
    @Inject protected RuntimeConfig _runtime;
    @Inject protected PlayerLocator _locator;
    @Inject protected PlayerNodeActions _playerActions;
    @Inject protected MoneyNodeActions _moneyActions;
    @Inject protected RootDObjectManager _omgr;
    @Inject protected InvocationManager _invmgr;
    @Inject protected WorldGameRegistry _wgameReg;
    @Inject protected GameWatcherManager _watchmgr;
    @Inject protected LocationManager _locmgr;
    @Inject protected PlaceRegistry _placeReg;
    @Inject protected BureauRegistry _bureauReg;
    @Inject protected StatLogic _statLogic;
    @Inject protected MoneyLogic _moneyLogic;
    @Inject protected MemberManager _memmgr;

    // various and sundry repositories for loading persistent data
    @Inject protected MsoyGameRepository _mgameRepo;
    @Inject protected AVRGameRepository _avrgRepo;
    @Inject protected RatingRepository _ratingRepo;
    @Inject protected FeedRepository _feedRepo;
    @Inject protected TrophyRepository _trophyRepo;
    @Inject protected LevelPackRepository _lpackRepo;
    @Inject protected ItemPackRepository _ipackRepo;
    @Inject protected TrophySourceRepository _tsourceRepo;
    @Inject protected PrizeRepository _prizeRepo;

    /** Period of game log deletion. */
    protected static final long LOG_DELETION_INTERVAL = 6 * 60 * 60 * 1000;

    /** The maximum number of different game modes we allow. */
    protected static final int MAX_GAME_MODES = 100;
}
