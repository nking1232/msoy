//
// $Id$

package com.threerings.msoy.room.server;

import com.threerings.io.SimpleStreamableObject;

import com.threerings.msoy.item.data.all.Pet;
import com.threerings.msoy.room.data.EntityMemories;

/**
 * Contains information forwarded between servers when a member moves thusly.
 */
public class PetLocal extends SimpleStreamableObject
{
    /** The pet being walked by this member. */
    public Pet pet;

    /** The pet's memories. */
    public EntityMemories memories;
}
