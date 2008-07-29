//
// $Id: AwardDelegate.java 9976 2008-07-23 16:14:05Z mdb $

package com.threerings.msoy.avrg.server;

import com.google.inject.Inject;

import com.samskivert.jdbc.WriteOnlyUnit;

import com.samskivert.util.HashIntMap;
import com.samskivert.util.IntMap;
import com.samskivert.util.Invoker;

import com.threerings.crowd.data.PlaceObject;
import com.threerings.crowd.server.PlaceManagerDelegate;
import com.threerings.whirled.data.ScenePlace;

import com.threerings.presents.util.PersistingUnit;
import com.threerings.presents.annotation.MainInvoker;
import com.threerings.presents.client.InvocationService;
import com.threerings.presents.data.ClientObject;
import com.threerings.presents.data.InvocationCodes;
import com.threerings.presents.server.InvocationException;

import com.threerings.msoy.admin.server.RuntimeConfig;
import com.threerings.msoy.server.MsoyEventLogger;
import com.threerings.msoy.server.persist.MemberRepository;
import com.threerings.msoy.item.data.all.Game;
import com.threerings.msoy.item.server.persist.GameRepository;

import com.threerings.msoy.avrg.data.AVRGameObject;
import com.threerings.msoy.avrg.server.persist.AVRGameRepository;
import com.threerings.msoy.avrg.server.persist.QuestLogSummaryRecord;

import com.threerings.msoy.data.UserAction;
import com.threerings.msoy.data.UserActionDetails;
import com.threerings.msoy.data.all.MemberName;

import com.threerings.msoy.game.data.PlayerObject;
import com.threerings.msoy.game.data.QuestState;
import com.threerings.msoy.game.server.GameContent;
import com.threerings.msoy.game.server.WorldServerClient;

import static com.threerings.msoy.Log.log;

/**
 * Handles quest-related AVRG services, including awarding coins.
 * TODO: This may or may not be included in a refactor involving AwardDelegate
 */
public class QuestDelegate extends PlaceManagerDelegate
{
    /**
     * Creates a Whirled game manager delegate with the supplied game content.
     */
    public QuestDelegate (GameContent content)
    {
        // keep our game content around for later
        _content = content;
        _gameId = _content.game.gameId;
    }

    @Override
    public void didStartup (PlaceObject plobj)
    {
        super.didStartup(plobj);
    }

    @Override
    public void didShutdown ()
    {
        super.didShutdown();

        final int totalMins = Math.max(1, Math.round(getTotalTrackedSeconds() / 60f));
        _invoker.postUnit(new WriteOnlyUnit("shutdown") {
            public void invokePersist () throws Exception {
                _repo.noteUnawardedTime(_gameId, totalMins);
            }
        });
        
    }

    @Override // from PlaceManagerDelegate
    public void bodyEntered (int bodyOid)
    {
        PlayerObject player = (PlayerObject) _omgr.getObject(bodyOid);
        if (_players.containsKey(bodyOid)) {
            log.warning("Attempting to re-add existing player [gameId=" + _gameId + ", playerId=" +
                        player.getMemberId() + "]");
            return;
        }
        _players.put(bodyOid, new Player(player));

        _worldClient.updatePlayer(player.getMemberId(), _content.game);
    }

    @Override // from PlaceManagerDelegate
    public void bodyLeft (int bodyOid)
    {
        Player player = _players.remove(bodyOid);
        if (player == null) {
            log.warning("Eek, unregistered player vanished from OidList [gameId=" + _gameId +
                        "bodyOid=" + bodyOid + "]");
            return;
        }

        int playTime = player.getPlayTime(now());
        _totalTrackedSeconds += playTime;

        int memberId = player.playerObject.getMemberId();

        // TODO: Get this into EventLogDelegate, or write a AVRG-specific one?
        String tracker = player.playerObject.referral.tracker;
        _eventLog.avrgLeft(memberId, _gameId, playTime,
                           _plmgr.getPlaceObject().occupantInfo.size(),
                           tracker);

        _worldClient.updatePlayer(memberId, null);
    }

    // from AVRGameProvider
    public void startQuest (ClientObject caller, final String questId, final String status,
                            InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final PlayerObject player = (PlayerObject) caller;
        if (questId == null) {
            log.warning("Received startQuest() request with null questId [game=" + where() +
                        ", who=" + player.who() + "]");
            throw new InvocationException(InvocationCodes.INTERNAL_ERROR);
        }

        if (player.questState.containsKey(questId)) {
            listener.requestProcessed(); // silently ignore
            return;
        }

        final int sceneId = ScenePlace.getSceneId(player);
        _invoker.postUnit(new PersistingUnit("startQuest(" + questId + ")", listener) {
            public void invokePersistent () throws Exception {
                if (!MemberName.isGuest(player.getMemberId())) {
                    _repo.setQuestState(_gameId, player.getMemberId(), questId,
                                        QuestState.STEP_FIRST, status, sceneId);
                }
            }
            public void handleSuccess () {
                player.addToQuestState(
                    new QuestState(questId, QuestState.STEP_FIRST, status, sceneId));
                reportRequestProcessed();
            }
        });
    }

    // from AVRGameProvider
    public void updateQuest (ClientObject caller, final String questId, final int step,
                             final String status, InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final PlayerObject player = (PlayerObject) caller;

        QuestState oldState = player.questState.get(questId);
        if (oldState == null) {
            log.warning("Requested to update quest to which member was not subscribed " +
                        "[game=" + where() + ", quest=" + questId + ", who=" + player.who() + "].");
            throw new InvocationException(InvocationCodes.INTERNAL_ERROR);
        }

        final int sceneId = ScenePlace.getSceneId(player);

        _invoker.postUnit(new PersistingUnit("updateQuest", listener) {
            public void invokePersistent () throws Exception {
                if (!MemberName.isGuest(player.getMemberId())) {
                    _repo.setQuestState(_gameId, player.getMemberId(), questId, step,
                                        status, sceneId);
                }
            }
            public void handleSuccess () {
                player.updateQuestState(new QuestState(questId, step, status, sceneId));
                reportRequestProcessed();
            }
        });
    }

    // from AVRGameProvider
    public void completeQuest (ClientObject caller, final String questId, final float payoutLevel,
                               InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final PlayerObject player = (PlayerObject) caller;
        final QuestState oldState = player.questState.get(questId);

        // very little is done for guests
        if (MemberName.isGuest(player.getMemberId())) {
            if (oldState != null) {
                player.removeFromQuestState(questId);
            }
            listener.requestProcessed();
            return;
        }

        // sanity check
        if (payoutLevel < 0 || payoutLevel > 1) {
            log.warning("Invalid payout in completeQuest() [game=" + where() + ", quest=" + questId +
                        ", payout=" + payoutLevel + ", caller=" + player.who() + "].");
            throw new InvocationException(InvocationCodes.INTERNAL_ERROR);
        }

        final int flowPerHour = RuntimeConfig.server.hourlyGameFlowRate;
        final int recalcMins = RuntimeConfig.server.payoutFactorReassessment;

        // note how much player time has accumulated since our last payout
        int playerSecs = getTotalTrackedSeconds();
        _totalTrackedSeconds -= playerSecs;
        final int playerMins = Math.round(playerSecs / 60f);

        // the tutorial pays out no flow; normal games depend on accumulated play time
        int payoutFactor;
        if (_gameId == Game.TUTORIAL_GAME_ID) {
            payoutFactor = 0;
        } else {
            // if we've yet to accumulate enough data for a calculation, guesstimate 5 mins
            payoutFactor = (_content.detail.payoutFactor == 0) ?
                (5 * flowPerHour/60) : _content.detail.payoutFactor;
        }

        // compute our quest payout; as a sanity check, cap it at one hour of payout
        int rawPayout = Math.round(payoutFactor * payoutLevel);
        final int payout = Math.min(flowPerHour, rawPayout);
        if (payout != rawPayout) {
            log.warning("Capped AVRG payout at one hour [game=" + _gameId +
                        ", factor=" + payoutFactor + ", level=" + payoutLevel +
                        ", wanted=" + rawPayout + ", got=" + payout + "].");
        }

        final int newFlowToNextRecalc;
        if (_content.detail == null) { // this is null if we're in the tutorial
            newFlowToNextRecalc = 0;
        } else {
            // note that we've used up some of our flow budget (this same adjustment will be
            // recorded to the database in noteGamePlayed())
            _content.detail.flowToNextRecalc -= payout;

            // if this payout consumed the remainder of our awardable flow, queue a factor recalc
            if (_content.detail.flowToNextRecalc <= 0) {
                newFlowToNextRecalc = flowPerHour * recalcMins + _content.detail.flowToNextRecalc;
                // update our in memory record immediately so that we don't encounter funny
                // business if another quest payout is done while we're writing this to the
                // database
                _content.detail.flowToNextRecalc = newFlowToNextRecalc;
            } else {
                newFlowToNextRecalc = 0;
            }
        }

        _invoker.postUnit(new PersistingUnit("completeQuest", listener) {
            public void invokePersistent () throws Exception {
                // award the flow for this quest
                if (payout > 0) {
                    _memberRepo.getFlowRepository().grantFlow(
                        new UserActionDetails(player.getMemberId(), UserAction.COMPLETED_QUEST,
                                              -1, Game.GAME, _gameId, questId), payout);
                }

                // note that we played one game and awarded the specified flow
                _gameRepo.noteGamePlayed(_gameId, 1, payout);

                // mark the quest completed and create a log record
                _repo.noteQuestCompleted(
                    _gameId, player.getMemberId(), questId, playerMins, payoutLevel);

                // if this award consumes the remainder of our awardable flow, recalc our bits
                if (newFlowToNextRecalc > 0) {
                    QuestLogSummaryRecord record = _repo.summarizeQuestLogRecords(_gameId);
                    if (record.payoutFactorTotal > 0) {
                        float targetFlow = flowPerHour * record.playerMinsTotal / 60f;
                        _newFactor = Math.round(targetFlow / record.payoutFactorTotal);

                        _gameRepo.updatePayoutFactor(_gameId, _newFactor, newFlowToNextRecalc);
                        _repo.deleteQuestLogRecords(_gameId);

                        log.info("Recalculation complete [factor=(" + flowPerHour + "*" +
                                 record.playerMinsTotal + ")/60f/" +
                                 record.payoutFactorTotal + " => " + _newFactor + "]");
                    }
                }
            }

            public void handleSuccess () {
                if (oldState != null) {
                    player.removeFromQuestState(questId);
                }

                // if we paid out flow, let any logged-on member objects know
                if (payout > 0) {
                    _worldClient.reportFlowAward(player.getMemberId(), payout);
                    // report to the game that this player earned some flow
                    player.postMessage(AVRGameObject.COINS_AWARDED_MESSAGE, payout, -1);
                }

                // if we updated the payout factor in the db, do it in dobj land too
                if (newFlowToNextRecalc > 0) {
                    _content.detail.payoutFactor = _newFactor;
                }

                reportRequestProcessed();
            }

            protected int _newFactor;
        });
    }

    // from AVRGameProvider
    public void cancelQuest (ClientObject caller, final String questId,
                             InvocationService.ConfirmListener listener)
        throws InvocationException
    {
        final PlayerObject player = (PlayerObject) caller;

        QuestState oldState = player.questState.get(questId);
        if (oldState == null) {
            log.warning("Member not subscribed to cancelled quest [game=" + where() +
                        ", quest=" + questId + ", who=" + player.who() + "].");
            throw new InvocationException(InvocationCodes.INTERNAL_ERROR);
        }

        _invoker.postUnit(new PersistingUnit("cancelQuest", listener) {
            public void invokePersistent () throws Exception {
                if (!MemberName.isGuest(player.getMemberId())) {
                    _repo.deleteQuestState(player.getMemberId(), _gameId, questId);
                }
            }
            public void handleSuccess () {
                player.removeFromQuestState(questId);
                reportRequestProcessed();
            }
        });
    }

    /**
     * Return the total number of seconds that players were being tracked.
     */
    protected int getTotalTrackedSeconds ()
    {
        int total = _totalTrackedSeconds;
        int now = now();

        for (Player player : _players.values()) {
            total += player.getPlayTime(now);
        }
        return total;
    }

    /**
     * Convenience method to calculate the current timestmap in seconds.
     */
    protected static int now ()
    {
        return (int) (System.currentTimeMillis() / 1000);
    }

    protected class Player
    {
        public PlayerObject playerObject;
        public int beganStamp;
        public int secondsPlayed;

        public Player (PlayerObject playerObject)
        {
            this.playerObject = playerObject;
            this.beganStamp = now();
        }

        public int getPlayTime (int now) {
            int secondsOfPlay = secondsPlayed;
            if (beganStamp != 0) {
                secondsOfPlay += (now - beganStamp);
            }
            return secondsOfPlay;
        }

        public void stopTracking (int endStamp) {
            if (beganStamp != 0) {
                secondsPlayed += endStamp - beganStamp;
                beganStamp = 0;
            }
        }
    }

    /** The gameId of this particular AVRG. */
    protected int _gameId;

    /** The metadata for the game for which we're lobbying. */
    protected GameContent _content;

    /** A map to track current AVRG player data, per PlayerObject Oid. */
    protected IntMap<Player> _players = new HashIntMap<Player>();

    /** Counts the total number of seconds that have elapsed during 'tracked' time, for each
     * tracked member that is no longer present with a Player object. */
    protected int _totalTrackedSeconds = 0;

    @Inject protected @MainInvoker Invoker _invoker;
    @Inject protected AVRGameRepository _repo;
    @Inject protected MemberRepository _memberRepo;
    @Inject protected GameRepository _gameRepo;
    @Inject protected WorldServerClient _worldClient;
    @Inject protected MsoyEventLogger _eventLog;
}
