//
// $Id$

package com.threerings.msoy.avrg.server;

import java.util.Map;

import com.whirled.game.data.PropertySpaceObject;
import com.whirled.game.server.PropertySpaceHelper;

import com.threerings.parlor.server.PlayManagerDelegate;

import com.threerings.msoy.avrg.data.AVRGameAgentObject;

/**
 * A delegate that initializes a {@link AVRGameAgentObject} (which should implement
 * {@link PropertySpaceObject}) with properties from persistent storage. The data
 * must have already been read from the database by the time the manager starts up.
 */
public abstract class AgentPropertySpaceDelegate extends PlayManagerDelegate
{
    protected abstract Map<String, byte[]> initialStateFromStore ();
    protected abstract void writeDirtyStateToStore (Map<String, byte[]> dirtyProps);

    public void initializeAgent (AVRGameAgentObject obj)
    {
        _psObj = obj;

        PropertySpaceHelper.initWithProperties(
            _psObj, PropertySpaceHelper.recordsToProperties(initialStateFromStore()), false);
    }

    @Override
    public void didShutdown ()
    {
        super.didShutdown();

        if (_psObj != null) {
            writeDirtyStateToStore(PropertySpaceHelper.encodeDirtyStateForStore(_psObj));
        }
    }

    protected PropertySpaceObject _psObj;
}
