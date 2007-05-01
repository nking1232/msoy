//
// $Id$

package com.threerings.msoy.game.data {

import com.threerings.io.TypedArray;
import com.threerings.io.ObjectInputStream;
import com.threerings.io.ObjectOutputStream;

import com.threerings.crowd.data.BodyObject;

import com.threerings.parlor.data.Table;
import com.threerings.parlor.data.TableConfig;
import com.threerings.parlor.game.data.GameConfig;

import com.threerings.msoy.data.MemberObject;

import com.threerings.msoy.item.data.all.MediaDesc;

/**
 * Extends the standard parlor {@link Table} with some custom data.
 */
public class MsoyTable extends Table
{
    /** Head shots for each occupant. */
    public var headShots :TypedArray;

    /** Suitable for unserialization. */
    public function MsoyTable ()
    {
    }

    // from interface Streamable
    override public function readObject (ins :ObjectInputStream) :void
    {
        super.readObject(ins);
        headShots = (ins.readObject() as TypedArray);
    }

    // from interface Streamable
    override public function writeObject (out :ObjectOutputStream) :void
    {
        super.writeObject(out);
        out.writeObject(headShots);
    }
}
}
