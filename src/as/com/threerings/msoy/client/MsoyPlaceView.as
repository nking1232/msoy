//
// $Id$

package com.threerings.msoy.client {

import com.threerings.crowd.client.PlaceView;

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
}
}
