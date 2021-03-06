//
// $Id$

package client.account;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.FlowPanel;

import com.threerings.msoy.web.gwt.SessionData;
import com.threerings.msoy.web.gwt.WebUserService;
import com.threerings.msoy.web.gwt.WebUserServiceAsync;

import client.shell.CShell;
import client.ui.MsoyUI;
import client.util.PageCallback;

/**
 * Handles the account validation process.
 */
public class ValidatePanel extends FlowPanel
{
    public ValidatePanel (int memberId, String code)
    {
        add(MsoyUI.createLabel(_msgs.validateChecking(), "infoLabel"));

        // validation received, show a quick message and move on to #people-confprof
        _usersvc.validateEmail(memberId, code, new PageCallback<SessionData>(this) {
            public void onSuccess (SessionData data) {
                if (data == null) {
                    clear();
                    add(MsoyUI.createLabel(_msgs.emailInvalid(), "infoLabel"));
                } else {
                    CShell.frame.dispatchDidLogon(data); // will redirect
                }
            }
        });
    }

    protected static final AccountMessages _msgs = GWT.create(AccountMessages.class);
    protected static final WebUserServiceAsync _usersvc = GWT.create(WebUserService.class);
}
