//
// $Id$

package com.threerings.msoy.web.server;

import java.util.ArrayList;

import com.threerings.msoy.server.MsoyServer;
import com.threerings.msoy.server.ServerConfig;

import com.threerings.msoy.web.client.SwiftlyService;
import com.threerings.msoy.web.data.ServiceException;

/**
 * Provides the server implementation of {@link SwiftlyService}.
 */
public class SwiftlyServlet extends MsoyServiceServlet
    implements SwiftlyService
{
    public String getRpcURL()
    {
        return "http://" + ServerConfig.serverHost + ":" + ServerConfig.getHttpPort() + "/" +
            SwiftlyEditorServlet.SVC_PATH + "/";
    }
}
