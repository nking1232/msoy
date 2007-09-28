//
// $Id$

package com.threerings.msoy.game.server;

import com.threerings.msoy.game.client.AVRGameService;
import com.threerings.presents.client.Client;
import com.threerings.presents.client.InvocationService;
import com.threerings.presents.data.ClientObject;
import com.threerings.presents.server.InvocationException;
import com.threerings.presents.server.InvocationProvider;

/**
 * Defines the server-side of the {@link AVRGameService}.
 */
public interface AVRGameProvider extends InvocationProvider
{
    /**
     * Handles a {@link AVRGameService#completeQuest} request.
     */
    public void completeQuest (ClientObject caller, String arg1, int arg2, InvocationService.ConfirmListener arg3)
        throws InvocationException;

    /**
     * Handles a {@link AVRGameService#deletePlayerProperty} request.
     */
    public void deletePlayerProperty (ClientObject caller, String arg1, InvocationService.ConfirmListener arg2)
        throws InvocationException;

    /**
     * Handles a {@link AVRGameService#deleteProperty} request.
     */
    public void deleteProperty (ClientObject caller, String arg1, InvocationService.ConfirmListener arg2)
        throws InvocationException;

    /**
     * Handles a {@link AVRGameService#setPlayerProperty} request.
     */
    public void setPlayerProperty (ClientObject caller, String arg1, byte[] arg2, InvocationService.ConfirmListener arg3)
        throws InvocationException;

    /**
     * Handles a {@link AVRGameService#setProperty} request.
     */
    public void setProperty (ClientObject caller, String arg1, byte[] arg2, InvocationService.ConfirmListener arg3)
        throws InvocationException;

    /**
     * Handles a {@link AVRGameService#startQuest} request.
     */
    public void startQuest (ClientObject caller, String arg1, String arg2, InvocationService.ConfirmListener arg3)
        throws InvocationException;

    /**
     * Handles a {@link AVRGameService#updateQuest} request.
     */
    public void updateQuest (ClientObject caller, String arg1, int arg2, String arg3, InvocationService.ConfirmListener arg4)
        throws InvocationException;
}
