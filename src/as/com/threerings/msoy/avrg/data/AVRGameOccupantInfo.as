//
// $Id$

package com.threerings.msoy.avrg.data {

import com.threerings.io.ObjectInputStream;

import com.threerings.util.Joiner;

import com.threerings.crowd.data.OccupantInfo;

import com.threerings.msoy.party.data.PartyOccupantInfo;

public class AVRGameOccupantInfo extends OccupantInfo
    implements PartyOccupantInfo
{
    // from PartyOccupantInfo
    public function getPartyId () :int
    {
        return _partyId;
    }

    override public function clone () :Object
    {
        var that :AVRGameOccupantInfo = super.clone() as AVRGameOccupantInfo;
        that._partyId = this._partyId;
        return that;
    }

    override public function readObject (ins :ObjectInputStream) :void
    {
        super.readObject(ins);
        _partyId = ins.readInt();
    }

    override protected function toStringJoiner (j :Joiner) :void
    {
        super.toStringJoiner(j);
        j.add("partyId", _partyId);
    }

    protected var _partyId :int;
}
}
