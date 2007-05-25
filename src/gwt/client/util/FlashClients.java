//
// $Id$

package client.util;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.http.client.URL;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.HTML;

import com.threerings.gwt.ui.WidgetUtil;

import com.threerings.msoy.web.client.DeploymentConfig;
import com.threerings.msoy.data.all.FriendEntry;
import com.threerings.msoy.data.all.MemberName;

/**
 * Utility methods for generating flash clients.
 */
public class FlashClients
{
    public static final String HOOD_SKIN_URL = "/media/static/hood_pastoral.swf";

    public static HTML createWorldClient (String flashVars)
    {
        return WidgetUtil.createFlashContainer(
            "asclient", "/clients/" + DeploymentConfig.version + "/world-client.swf",
            "100%", getClientHeight(false), flashVars);
    }

    public static HTML createLobbyClient (int gameId, String token)
    {
        return WidgetUtil.createFlashContainer(
            "asclient", "/clients/" + DeploymentConfig.version + "/world-client.swf",
            "100%", getClientHeight(false), "gameLobby=" + gameId + "&token=" + token);
    }

    public static HTML createNeighborhood (String hoodData)
    {
        return createNeighborhood(hoodData, "100%", getClientHeight(true));
    }

    public static HTML createNeighborhood (String hoodData, String width, String height)
    {
        return WidgetUtil.createFlashContainer(
            "hood","/media/static/HoodViz.swf", width, height,
            "skinURL= " + HOOD_SKIN_URL + "&neighborhood=" + hoodData);
    }

    public static HTML createPopularPlaces (String hotspotData)
    {
        return WidgetUtil.createFlashContainer(
            "hotspots","/media/static/HoodViz.swf", "100%", getClientHeight(true),
            "skinURL= " + HOOD_SKIN_URL + "&neighborhood=" + hotspotData);
    }

    public static HTML createAvatarViewer (String avatarPath, float scale)
    {
        return WidgetUtil.createFlashContainer(
            "avatarViewer", "/clients/" + DeploymentConfig.version + "/avatarviewer.swf",
            360, 450, "avatar=" + URL.encodeComponent(avatarPath) + "&scale=" + scale);
    }

    public static HTML createVideoViewer (String videoPath)
    {
        return WidgetUtil.createFlashContainer(
            "videoViewer", "/clients/" + DeploymentConfig.version + "/videoviewer.swf",
            320, 240, "video=" + URL.encodeComponent(videoPath));
    }

    public static HTML createDecorViewer ()
    {
        return WidgetUtil.createFlashContainer(
            "decorViewer", "/clients/" + DeploymentConfig.version + "/decorviewer.swf",
            300, 300, "");
    }

    /**
     * Calls into the Flash client and gets the list of our friends.
     */
    public static FriendEntry[] getFriends ()
    {
        JavaScriptObject result = getFriendsNative();
        int length = (result == null ? 0 : getLength(result)/3);
        FriendEntry[] friends = new FriendEntry[length];
        for (int ii = 0; ii < friends.length; ii++) {
            friends[ii] = new FriendEntry();
            friends[ii].name = new MemberName(getStringElement(result, 3*ii),
                                              getIntElement(result, 3*ii+1));
            friends[ii].online = getBooleanElement(result, 3*ii+2);
        }
        return friends;
    }

    /**
     * Loads our flow gold, and experience levels.
     */
    public static int[] getLevels ()
    {
        int[] levels = new int[3];
        JavaScriptObject result = getLevelsNative();
        int length = (result == null ? 0 : getLength(result));
        for (int ii = 0; ii < length; ii++) {
            levels[ii] = getIntElement(result, ii);
        }
        return levels;
    }

    /**
     * Loads the mail notification status.
     */
    public static boolean getMailNotification ()
    {
        return getBoolean(getMailNotificationNative());
    }

    /**
     * Checks if the flash client can be found on this page.
     */
    public static boolean clientExists ()
    {
        return clientExistsNative();
    }

    /** 
     * Checks with the actionscript client to find out if our current scene is in fact a room.
     */
    public static boolean inRoom ()
    {
        return inRoomNative();
    }

    /**
     * Tells the actionscript client that we'd like to add this piece of furni to the 
     * current room.  If there is a reason that we can't do this, it is handled by the 
     * actionscript client.
     */
    public static void addFurni (int itemId, byte itemType) 
    {
        addFurniNative(itemId, itemType);
    }

    /**
     * Computes the height to use for our Flash clients based on the smaller of our desired client
     * height and the vertical room available minus the header and an annoying "we don't know how
     * to implement scrollbars" bullshit browser factor.
     */
    protected static String getClientHeight (boolean subtractBlackBarHeight)
    {
        int height = Math.min(Window.getClientHeight()-HEADER_HEIGHT-1, CLIENT_HEIGHT);
        if (subtractBlackBarHeight) {
            height -= BLACKBAR_HEIGHT;
        }
        return String.valueOf(height);
    }

    /**
     * Does the actual JavaScript <code>getFriends</code> call.
     */
    protected static native JavaScriptObject getFriendsNative () /*-{
        var client = $doc.getElementById("asclient");
        if (client) {
            try {
                return client.getFriends();
            } catch (e) {
                // fall through
            }
        }
        return null;
    }-*/;

    /**
     * Does the actual JavaScript <code>getLevels</code> call.
     */
    protected static native JavaScriptObject getLevelsNative () /*-{
        var client = $doc.getElementById("asclient");
        if (client) {
            try {
                return client.getLevels();
            } catch (e) {
                // fall through
            }
        }
        return null;
    }-*/;

    /**
     * Does the actual JavaScript <code>getMailNotification</code> call.
     */
    protected static native JavaScriptObject getMailNotificationNative () /*-{
        var client = $doc.getElementById("asclient");
        if (client) {
            try {
                return client.getMailNotification();
            } catch (e) {
                // fall through
            }
        }
        return null;
    }-*/;

    /** 
     * Does the actual <code>clientExists()</code> call.
     */
    protected static native boolean clientExistsNative () /*-{
        return $doc.getElementById("asclient") != null;
    }-*/;

    /**
     * Does the actual <code>inRoom()</code> call.
     */
    protected static native boolean inRoomNative () /*-{
        var client = $doc.getElementById("asclient");
        if (client) {
            try {
                return client.inRoom();
            } catch (e) {
                // fall through
            }
        } 
        return false;
    }-*/;

    /**
     * Does the actual <code>addFurni()</code> call.
     */
    protected static native void addFurniNative (int itemId, byte itemType) /*-{
        var client = $doc.getElementById("asclient");
        if (client) {
            client.addFurni(itemId, itemType);
        }
    }-*/;

    /**
     * Helpy helper function.
     */
    protected static native int getLength (JavaScriptObject array) /*-{
        return array.length;
    }-*/;

    /**
     * Helpy helper function.
     */
    protected static native String getStringElement (JavaScriptObject array, int index) /*-{
        return array[index];
    }-*/;

    /**
     * Helpy helper function.
     */
    protected static native int getIntElement (JavaScriptObject array, int index) /*-{
        return array[index];
    }-*/;

    /**
     * Helpy helper function.
     */
    protected static native boolean getBooleanElement (JavaScriptObject array, int index) /*-{
        return array[index];
    }-*/;

    /**
     * Helpy helper function.
     */
    protected static native boolean getBoolean (JavaScriptObject value) /*-{
        return value;
    }-*/;

    // TODO: put this in Application?
    protected static final int HEADER_HEIGHT = 50;
    protected static final int BLACKBAR_HEIGHT = 20;
    protected static final int CLIENT_HEIGHT = 550;
}
