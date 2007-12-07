//
// $Id$

package com.threerings.msoy.client {

import com.threerings.crowd.client.PlaceView;

import com.threerings.msoy.chat.client.ChatOverlay;

/**
 * An expanded PlaceView interface that can be used by views that wish to learn about their actual
 * pixel dimensions.
 */
public interface MsoyPlaceView extends PlaceView, PlaceLayer
{
    /**
     * Inform the place view whether or not it's showing.
     */
    function setIsShowing (showing :Boolean) :void;

    /**
     * Whether or not to put whitespace above and below this view.
     */
    function padVertical () :Boolean;

    /**
     * Returns this view's chat overlay or null.
     */
    function getChatOverlay () :ChatOverlay;

    /**
     * Tells this view whether or not to display a chat overlay.
     */
    function setUseChatOverlay (useOverlay :Boolean) :void;
}
}
