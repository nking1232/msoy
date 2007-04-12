//
// $Id$

package com.threerings.msoy.game.client {

import flash.display.DisplayObject;
import flash.display.DisplayObjectContainer;
import flash.display.Sprite;

import flash.events.MouseEvent;
import flash.events.TextEvent;

import mx.collections.ArrayCollection;

import mx.containers.HBox;
import mx.containers.VBox;
import mx.containers.ViewStack;
import mx.controls.Label;
import mx.controls.Alert;
import mx.controls.Text;
import mx.controls.TabBar;

import mx.core.Container;
import mx.core.ClassFactory;

import com.threerings.util.ArrayUtil;
import com.threerings.util.CommandEvent;

import com.threerings.flash.MediaContainer;

import com.threerings.flex.CommandButton;

import com.threerings.parlor.client.SeatednessObserver;
import com.threerings.parlor.client.TableDirector;
import com.threerings.parlor.client.TableObserver;

import com.threerings.parlor.data.Table;

import com.threerings.parlor.game.data.GameConfig;

import com.threerings.msoy.client.Msgs;
import com.threerings.msoy.client.MsoyController;
import com.threerings.msoy.client.WorldContext;
import com.threerings.msoy.client.HeaderBarController;

import com.threerings.msoy.chat.client.ChatContainer;

import com.threerings.msoy.ui.MsoyList;
import com.threerings.msoy.ui.MediaWrapper;
import com.threerings.msoy.ui.ScalingMediaContainer;

import com.threerings.msoy.game.data.LobbyObject;
import com.threerings.msoy.item.web.Game;

import com.threerings.msoy.world.client.RoomView;

/**
 * A panel that displays pending table games.
 */
public class LobbyPanel extends VBox 
    implements TableObserver, SeatednessObserver
{
    /** Our log. */
    private const log :Log = Log.getLog(LobbyPanel);

    /** The lobby controller. */
    public var controller :LobbyController;

    /** The create-a-table button. */
    public var createBtn :CommandButton;

    /**
     * Create a new LobbyPanel.
     */
    public function LobbyPanel (ctx :WorldContext, ctrl :LobbyController)
    {
        _ctx = ctx;
        controller = ctrl;

        width = LOBBY_PANEL_WIDTH;
    }

    public function init (lobbyObj :LobbyObject) :void
    {
        _lobbyObj = lobbyObj;
        // add all preexisting tables
        for each (var table :Table in _lobbyObj.tables.toArray()) {
            tableAdded(table);
        }

        // fill in the UI bits
        var game :Game = getGame();
        _title.text = game.name;
        _title.validateNow();
        if ((_title.textWidth + 5) > 160) {
            // for some stupid reason, setting label.width = label.textWidth doesn't actually give
            // it enough room to display all the text, and you get the truncated version of the 
            // text - so we have to give the width a little extra room.
            _title.width = _title.textWidth + 5;
        }
        _about.text = Msgs.GAME.get("b.about");
        var thisLobbyPanel :LobbyPanel = this;
        _about.addEventListener(MouseEvent.CLICK, function () :void {
            CommandEvent.dispatch(thisLobbyPanel, MsoyController.VIEW_ITEM, game.getIdent());
        });
        // if ownerId = 0, we were pushed to the catalog's copy, so this is buyable
        if (game.ownerId == 0) {
            _buy.text = Msgs.GAME.get("b.buy");
            _buy.addEventListener(MouseEvent.CLICK, function () :void {
                CommandEvent.dispatch(thisLobbyPanel, MsoyController.VIEW_ITEM, game.getIdent());
            });
        } else {
            _buy.parent.removeChild(_buy);
        }

        _logo.addChild(new MediaWrapper(new MediaContainer(getGame().getThumbnailPath())));
        _info.text = game.description;
        _gameAvatarIcon = new ScalingMediaContainer(30, 30);
        _gameAvatarIcon.setMedia(getGame().getThumbnailPath());

        _tablesBox.removeAllChildren();
        createTablesDisplay();
    }

    /**
     * Returns the configuration for the game we're currently matchmaking.
     */
    public function getGame () :Game
    {
        return _lobbyObj != null ? _lobbyObj.game : null;
    }

    // from TableObserver
    public function tableAdded (table :Table) :void
    {
        if (_runningTables != null && table.occupants.length == 1) {
            _runningTables.addItem(table);
        } else {
            _formingTables.addItem(table);
        }
    }

    // from TableObserver
    public function tableUpdated (table :Table) :void
    {
        var idx :int = ArrayUtil.indexOf(_formingTables.source, table);
        if (idx >= 0) {
            if (table.gameOid != -1 && GameConfig.SEATED_GAME == 
                getGame().getGameDefinition().gameType) {
                _formingTables.removeItemAt(idx);
                _runningTables.addItem(table);
            } else {
                _formingTables.setItemAt(table, idx);
            }
        } else {
            idx = ArrayUtil.indexOf(_runningTables.source, table);
            if (idx >= 0) {
                _runningTables.setItemAt(table, idx);
            } else {
                log.warning("Never found table to update: " + table);
            }
        }
    }

    // from TableObserver
    public function tableRemoved (tableId :int) :void
    {
        var table :Table;
        for (var ii :int = 0; ii < _runningTables.length; ii++) {
            table = (_runningTables.getItemAt(ii) as Table);
            if (table.tableId == tableId) {
                _runningTables.removeItemAt(ii);
                return;
            }
        }
        for (ii = 0; ii < _formingTables.length; ii++) {
            table = (_formingTables.getItemAt(ii) as Table);
            if (table != null && table.tableId == tableId) {
                _formingTables.removeItemAt(ii);
                return;
            }
        }

        log.warning("Never found table to remove: " + tableId);
    }

    // from SeatednessObserver
    public function seatednessDidChange (isSeated :Boolean) :void
    {
        Log.getLog(this).debug("isSeated:" + isSeated);
        _isSeated = isSeated;
        createBtn.enabled = !isSeated;
        if (_isSeated) {
            (_ctx.getTopPanel().getPlaceView() as RoomView).getMyAvatar().   
                addDecoration(_gameAvatarIcon);
            CommandEvent.dispatch(this, LobbyController.LEAVE_LOBBY);
        } else {
            (_ctx.getTopPanel().getPlaceView() as RoomView).getMyAvatar().
                removeDecoration(_gameAvatarIcon);
        }
    }

    public function isSeated () :Boolean
    {
        return _isSeated;
    }

    override protected function createChildren () :void
    {
        super.createChildren();
        styleName = "lobbyPanel";
        percentHeight = 100;

        var titleBox :HBox = new HBox();
        titleBox.styleName = "titleBox";
        titleBox.percentWidth = 100;
        titleBox.height = 20;
        addChild(titleBox);
        _title = new Label();
        _title.styleName = "locationName";
        _title.width = 160;
        titleBox.addChild(_title);
        var padding :HBox = new HBox();
        padding.percentWidth = 100;
        padding.percentHeight = 100;
        titleBox.addChild(padding);
        _about = new Label();
        _about.styleName = "lobbyLink";
        titleBox.addChild(_about);
        _buy = new Label();
        _buy.styleName = "lobbyLink";
        titleBox.addChild(_buy);
        /****** TEMP - shows the same Embed button as the TopPanel for more usable usability ***/
        var embedBtnBox :VBox = new VBox();
        embedBtnBox.styleName = "headerEmbedBox";
        embedBtnBox.percentHeight = 100;
        titleBox.addChild(embedBtnBox);
        var embedBtn :CommandButton = new CommandButton(HeaderBarController.SHOW_EMBED_HTML);
        embedBtn.styleName = "embedButton";
        embedBtn.toolTip = Msgs.GENERAL.get("b.embed");
        embedBtnBox.addChild(embedBtn);
        /****** END TEMP ***/
        var leaveBtnBox :VBox = new VBox();
        leaveBtnBox.styleName = "lobbyCloseBox";
        leaveBtnBox.percentHeight = 100;
        titleBox.addChild(leaveBtnBox);
        var leaveBtn :CommandButton = new CommandButton(LobbyController.LEAVE_LOBBY);
        leaveBtn.styleName = "closeButton";
        leaveBtnBox.addChild(leaveBtn);

        var borderedBox :VBox = new VBox();
        addChild(borderedBox);
        borderedBox.styleName = "borderedBox";
        borderedBox.percentWidth = 100;
        borderedBox.percentHeight = 100;
        var descriptionBox :HBox = new HBox();
        descriptionBox.percentWidth = 100;
        descriptionBox.height = 124; // make room for padding at top
        descriptionBox.styleName = "descriptionBox";
        borderedBox.addChild(descriptionBox);
        _logo = new VBox();
        _logo.styleName = "lobbyLogoBox";
        _logo.width = 160;
        _logo.height = 120;
        descriptionBox.addChild(_logo);
        var infoBox :HBox = new HBox();
        infoBox.styleName = "infoBox";
        infoBox.percentWidth = 100;
        infoBox.percentHeight = 100;
        descriptionBox.addChild(infoBox);
        _info = new Text();
        _info.styleName = "lobbyInfo";
        _info.percentWidth = 100;
        _info.percentHeight = 100;
        infoBox.addChild(_info);

        _tablesBox = new VBox();
        _tablesBox.styleName = "tablesBox";
        _tablesBox.percentWidth = 100;
        _tablesBox.percentHeight = 100;
        borderedBox.addChild(_tablesBox);
        var loadingLabel :Label = new Label();
        loadingLabel.text = Msgs.GAME.get("l.gameLoading");
        _tablesBox.addChild(loadingLabel);
    }

    protected function createTablesDisplay () :void
    {
        // our game table data
        var list :MsoyList = new MsoyList(_ctx);
        list.styleName = "lobbyTableList";
        list.variableRowHeight = true;
        list.percentHeight = 100;
        list.percentWidth = 100;
        list.selectable = false;
        var factory :ClassFactory = new ClassFactory(TableRenderer);
        factory.properties = { ctx: _ctx, panel: this };
        list.itemRenderer = factory;
        list.dataProvider = _formingTables;

        // only display tabs for seated games
        if (getGame().getGameDefinition().gameType == GameConfig.SEATED_GAME) {
            var tabsBox :HBox = new HBox();
            tabsBox.styleName = "tabsBox";
            tabsBox.percentWidth = 100;
            tabsBox.height = 20;
            _tablesBox.addChild(tabsBox);
            var tabFiller :HBox = new HBox();
            tabFiller.styleName = "tabsFillerBox";
            tabFiller.width = 5;
            tabFiller.height = 9;
            tabsBox.addChild(tabFiller);
            var tabBar :TabBar = new TabBar();
            tabBar.percentHeight = 100;
            tabBar.styleName = "lobbyTabs";
            tabsBox.addChild(tabBar);
            tabFiller = new HBox();
            tabFiller.styleName = "tabsFillerBox";
            tabFiller.percentWidth = 100;
            tabFiller.height = 9;
            tabsBox.addChild(tabFiller);

            var tabViews :ViewStack = new ViewStack();
            tabViews.percentHeight = 100;
            tabViews.percentWidth = 100;
            _tablesBox.addChild(tabViews);
            tabBar.dataProvider = tabViews;
            var formingBox :VBox = new VBox();
            formingBox.percentHeight = 100;
            formingBox.percentWidth = 100;
            formingBox.label = Msgs.GAME.get("t.forming");
            tabViews.addChild(formingBox);
            formingBox.addChild(list);

            var runningList :MsoyList = new MsoyList(_ctx);
            runningList.styleName = "lobbyTableList";
            runningList.variableRowHeight = true;
            runningList.percentHeight = 100;
            runningList.percentWidth = 100;
            runningList.selectable = false;
            var runningFactory :ClassFactory = new ClassFactory(TableRenderer);
            runningFactory.properties = { ctx: _ctx, panel: this };
            runningList.itemRenderer = runningFactory;
            runningList.dataProvider = _runningTables;
            var runningBox :VBox = new VBox();
            runningBox.percentHeight = 100;
            runningBox.percentWidth = 100;
            runningBox.label = Msgs.GAME.get("t.running");
            runningBox.addChild(runningList);
            tabViews.addChild(runningBox);
        } else {
            var bar :HBox = new HBox();
            bar.styleName = "tabsFillerBox"; 
            bar.percentWidth = 100;
            bar.height = 9;
            _tablesBox.addChild(bar);
            _tablesBox.addChild(list);
        }
        if (_formingTables.source.length > 0) {
            _formingTables.setItemAt(null, 0);
        } else {
            _formingTables.addItem(null);
        }
    }

    protected static const LOBBY_PANEL_WIDTH :int = 500; // in px

    /** Buy one get one free. */
    protected var _ctx :WorldContext;

    /** Our lobby object. */
    protected var _lobbyObj :LobbyObject;

    /** Are we seated? */
    protected var _isSeated :Boolean;

    /** The currently forming tables. */
    protected var _formingTables :ArrayCollection = new ArrayCollection();

    /** The currently running tables. */
    protected var _runningTables :ArrayCollection = new ArrayCollection();

    // various UI bits that need filling in with data arrives
    protected var _logo :VBox;
    protected var _info :Text;
    protected var _title :Label;
    protected var _about :Label;
    protected var _buy :Label;
    protected var _tablesBox :VBox;

    protected var _gameAvatarIcon :ScalingMediaContainer;
}
}
