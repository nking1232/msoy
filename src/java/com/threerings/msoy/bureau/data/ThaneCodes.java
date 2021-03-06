//
// $Id$

package com.threerings.msoy.bureau.data;

import com.threerings.presents.data.InvocationCodes;

/**
 * Codes for operations between the server and thane clients.
 */
@com.threerings.util.ActionScript(omit=true)
public interface ThaneCodes extends InvocationCodes
{
    /**
     * Identifies thane bootstrap services.
     */
    public static final String THANE_GROUP = "thane";
}
