package com.threerings.msoy.client {

import flash.display.DisplayObject;
import flash.display.DisplayObjectContainer;
import flash.display.Stage;

import mx.core.Application;

import mx.managers.ISystemManager;

import com.threerings.util.MessageBundle;
import com.threerings.util.MessageManager;

import com.threerings.presents.client.Client;
import com.threerings.presents.dobj.DObjectManager;

import com.threerings.crowd.client.LocationDirector;
import com.threerings.crowd.client.OccupantDirector;
import com.threerings.crowd.client.PlaceView;

import com.threerings.crowd.chat.client.ChatDirector;

import com.threerings.parlor.client.ParlorDirector;
import com.threerings.parlor.util.ParlorContext;

import com.threerings.whirled.client.SceneDirector;
import com.threerings.whirled.spot.client.SpotSceneDirector;
import com.threerings.whirled.util.WhirledContext;

import com.threerings.msoy.client.persist.SharedObjectSceneRepository;
import com.threerings.msoy.data.MemberObject;

import com.threerings.msoy.item.client.ItemDirector;

import com.threerings.msoy.game.client.GameDirector;

public class MsoyContext
    implements WhirledContext, ParlorContext
{
    public function MsoyContext (client :Client)
    {
        _client = client;

        // initialize the message manager
        _msgMgr = new MessageManager(
            (Application.application.root as ISystemManager));
        // and our convenience holder
        Msgs.init(this);

        _helper = new ContextHelper();

        _locDir = new LocationDirector(this);
        _occDir = new OccupantDirector(this);
        _chatDir = new ChatDirector(this, _msgMgr, "general");
        _chatDir.setChatterValidator(_helper);
        _chatDir.addChatFilter(new CurseFilter(this));
        _sceneRepo = new SharedObjectSceneRepository()
        _sceneDir = new SceneDirector(this, _locDir, _sceneRepo,
            new MsoySceneFactory());
        _spotDir = new SpotSceneDirector(this, _locDir, _sceneDir);
        _mediaDir = new MediaDirector(this);
        _parlorDir = new ParlorDirector(this);
        _gameDir = new GameDirector(this);
        _memberDir = new MemberDirector(this);
        _itemDir = new ItemDirector(this);

        // set up the top panel
        _topPanel = new TopPanel(this);
        _controller = new MsoyController(this, _topPanel);
    }

    /**
     * Convenience method.
     */
    public function displayFeedback (bundle :String, message :String) :void
    {
        _chatDir.displayFeedback(bundle, message);
    }

    /**
     * Convenience method.
     */
    public function displayInfo (bundle :String, message :String) :void
    {
        _chatDir.displayInfo(bundle, message);
    }

    // from PresentsContext
    public function getClient () :Client
    {
        return _client;
    }

    public function getRootPanel () :DisplayObjectContainer
    {
        return Application(Application.application);
    }

    /**
     * Convenience method.
     */
    public function getClientObject () :MemberObject
    {
        return (_client.getClientObject() as MemberObject);
    }

    // from PresentsContext
    public function getDObjectManager () :DObjectManager
    {
        return _client.getDObjectManager();
    }

    // from CrowdContext
    public function getLocationDirector () :LocationDirector
    {
        return _locDir;
    }

    // from CrowdContext
    public function getOccupantDirector () :OccupantDirector
    {
        return _occDir;
    }

    // from CrowdContext
    public function getChatDirector () :ChatDirector
    {
        return _chatDir;
    }

    // from WhirledContext
    public function getSceneDirector () :SceneDirector
    {
        return _sceneDir;
    }

    // from ParlorContext
    public function getParlorDirector () :ParlorDirector
    {
        return _parlorDir;
    }

    /**
     * Get the GameDirector.
     */
    public function getGameDirector () :GameDirector
    {
        return _gameDir;
    }

    /**
     * Get the MemberDirector.
     */
    public function getMemberDirector () :MemberDirector
    {
        return _memberDir;
    }

    /**
     * Get the ItemDirector.
     */
    public function getItemDirector () :ItemDirector
    {
        return _itemDir;
    }

    /**
     * Get the SpotSceneDirector.
     */
    public function getSpotSceneDirector () :SpotSceneDirector
    {
        return _spotDir;
    }

    /**
     * Get the media director.
     */
    public function getMediaDirector () :MediaDirector
    {
        return _mediaDir;
    }

    /**
     * Get the message manager.
     */
    public function getMessageManager () :MessageManager
    {
        return _msgMgr;
    }

    /**
     * Get the top-level msoy controller.
     */
    public function getMsoyController () :MsoyController
    {
        return _controller;
    }

    // documentation inherited from superinterface CrowdContext
    public function setPlaceView (view :PlaceView) :void
    {
        _topPanel.setPlaceView(view);
    }

    // documentation inherited from superinterface CrowdContext
    public function clearPlaceView (view :PlaceView) :void
    {
        _topPanel.clearPlaceView(view);
    }

    public function getTopPanel () :TopPanel
    {
        return _topPanel;
    }

    /**
     * Convenience translation method. If the first arg imethod to translate a key using the general bundle.
     */
    public function xlate (bundle :String, key :String, ... args) :String
    {
        args.unshift(key);
        if (bundle == null) {
            bundle = "general";
        }
        var mb :MessageBundle = _msgMgr.getBundle(bundle);
        return mb.get.apply(mb, args);
    }

    public function TEMPClearSceneCache () :void
    {
        _sceneRepo.TEMPClearSceneCache();
    }

    protected var _client :Client;

    protected var _helper :ContextHelper;

    protected var _topPanel :TopPanel;

    protected var _controller :MsoyController;

    protected var _msgMgr :MessageManager;

    protected var _locDir :LocationDirector;

    protected var _occDir :OccupantDirector;

    protected var _sceneDir :SceneDirector;

    protected var _chatDir :ChatDirector;

    protected var _spotDir :SpotSceneDirector;

    protected var _mediaDir :MediaDirector;

    protected var _parlorDir :ParlorDirector;

    protected var _gameDir :GameDirector;

    protected var _memberDir :MemberDirector;

    protected var _itemDir :ItemDirector;

    protected var _sceneRepo :SharedObjectSceneRepository;
}
}

import com.threerings.util.Name;

import com.threerings.crowd.chat.client.ChatterValidator;

import com.threerings.msoy.web.data.MemberName;

/**
 * A helper class that implements common helper interfaces that we would
 * not like to see exposed on the MsoyContext class.
 * In Java, this would be handled by having a number of anonymous inner
 * classes.
 */
class ContextHelper
    implements ChatterValidator
{
    // from ChatterValidator
    public function isChatterValid (username :Name) :Boolean
    {
        return (username is MemberName) &&
            (MemberName.GUEST_ID != (username as MemberName).getMemberId());
    }
}
