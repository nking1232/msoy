package client.me;

import java.util.Date;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.AbsolutePanel;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.ClickListener;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.PushButton;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

import com.threerings.gwt.ui.Anchor;
import com.threerings.gwt.ui.WidgetUtil;

import com.threerings.msoy.data.all.MemberName;
import com.threerings.msoy.web.data.FeaturedGameInfo;
import com.threerings.msoy.web.data.GroupCard;
import com.threerings.msoy.web.data.ListingCard;
import com.threerings.msoy.web.data.LandingData;

import client.images.landing.LandingImages;
import client.shell.Application;
import client.shell.LogonPanel;
import client.shell.Page;
import client.util.MsoyCallback;
import client.util.MsoyUI;
import client.util.RoundBox;
import client.whirleds.FeaturedWhirledPanel;

/**
 * Displays a summary of what Whirled is, featuring games, avatars and whirleds.
 * Spans the entire width of the page, with an active content area 800 pixels wide and centered.
 */
public class LandingPanel extends SimplePanel
{
    public LandingPanel ()
    {
        // LandingPanel contains LandingBackground contains LandingContent
        setStyleName("LandingPanel");
        SimplePanel headerBackground = new SimplePanel();
        headerBackground.setStyleName("LandingBackground");
        AbsolutePanel content = new AbsolutePanel();
        content.setStyleName("LandingContent");
        headerBackground.setWidget(content);
        this.setWidget(headerBackground);

        // splash with animated characters (left goes over right)
        final HTML titleAnimation = WidgetUtil.createTransparentFlashContainer(
            "preview", "/images/landing/splash_left.swf", 500, 300, null);
        content.add(titleAnimation, -23, 10);

        // join now
        final Button joinButton =
            new Button("", Application.createLinkListener(Page.ACCOUNT, "create"));
        joinButton.setStyleName("JoinButton");
        content.add(joinButton, 475, 0);

        // login box
        final FlowPanel login = new FlowPanel();
        PushButton loginButton = new PushButton(CMe.msgs.landingLogin());
        loginButton.addStyleName("LoginButton");
        login.add(new LogonPanel(true, loginButton, true));
        login.add(loginButton);
        content.add(login, 590, 0);

        // intro video with click-to-play button
        final AbsolutePanel video = new AbsolutePanel();
        video.setStyleName("Video");
        ClickListener onClick = new ClickListener() {
            public void onClick (Widget sender) {
                video.remove(0);
                CMe.app.reportEvent("/me/video");
                // slideshow actual size is 360x260
                video.add(WidgetUtil.createFlashContainer(
                        "preview", "/images/landing/slideshow.swf", 200, 140, null), 38, 9);
            }
        };
        final Image clickToPlayImage = MsoyUI.createActionImage(
                "/images/landing/play_screen.png", CMe.msgs.landingClickToStart(), onClick);
        video.add(clickToPlayImage, 0, 0);
        content.add(video, 465, 90);

        // tagline
        final HTML tagline = new HTML(CMe.msgs.landingTagline());
        tagline.setStyleName("LandingTagline");
        content.add(tagline, 425, 275);

        // background for the rest of the page
        final FlowPanel background = new FlowPanel();
        background.setStyleName("Background");
        final FlowPanel leftBorder = new FlowPanel();
        leftBorder.setStyleName("LeftBorder");
        background.add(leftBorder);
        final FlowPanel center = new FlowPanel();
        center.setStyleName("Center");
        background.add(center);
        final FlowPanel rightBorder = new FlowPanel();
        rightBorder.setStyleName("RightBorder");
        background.add(rightBorder);
        content.add(background, 0, 310);

        // top games
        final RoundBox games = new RoundBox(RoundBox.DARK_BLUE);
        final TopGamesPanel topGamesPanel = new TopGamesPanel();
        games.add(topGamesPanel);
        content.add(games, 68, 312);

        // featured avatar
        content.add(_avatarPanel = new AvatarPanel(), 67, 618);

        // featured whirled panel is beaten into place using css
        _featuredWhirled = new FeaturedWhirledPanel(true, true);
        content.add(_featuredWhirled, 290, 618);

        // copyright, about, terms & conditions, help
        FlowPanel copyright = new FlowPanel();
        copyright.setStyleName("LandingCopyright");
        int year = 1900 + new Date().getYear();
        copyright.add(MsoyUI.createHTML(CMe.msgs.landingCopyright(""+year), "inline"));
        copyright.add(MsoyUI.createHTML("&nbsp;|&nbsp;", "inline"));
        copyright.add(makeLink("http://www.threerings.net", CMe.msgs.landingAbout()));
        copyright.add(MsoyUI.createHTML("&nbsp;|&nbsp;", "inline"));
        copyright.add(makeLink(
            "http://wiki.whirled.com/Terms_of_Service", CMe.msgs.landingTerms()));
        copyright.add(MsoyUI.createHTML("&nbsp;|&nbsp;", "inline"));
        copyright.add(makeLink("http://www.threerings.net/about/privacy.html",
                               CMe.msgs.landingPrivacy()));
        copyright.add(MsoyUI.createHTML("&nbsp;|&nbsp;", "inline"));
        copyright.add(Application.createLink(CMe.msgs.landingHelp(), Page.HELP, ""));
        content.add(copyright, 48, 970);

        // collect the data for this page
        CMe.worldsvc.getLandingData(new MsoyCallback<LandingData>() {
            public void onSuccess (LandingData data) {
//                topGamesPanel.setGames((FeaturedGameInfo[])data.topGames);
                FeaturedGameInfo[] topGames = new FeaturedGameInfo[1];
                FeaturedGameInfo dummy = new FeaturedGameInfo();
                dummy.creator = new MemberName("matt", 1);
                dummy.gameId = 1;
                topGames[0] = dummy;
                topGamesPanel.setGames(topGames);
                _featuredWhirled.setWhirleds((GroupCard[])data.featuredWhirleds);
                _avatarPanel.setAvatars((ListingCard[])data.topAvatars);
            }
        });
    }

    protected Widget makeLink (String url, String title)
    {
        Anchor anchor = new Anchor(url, title, "_blank");
        anchor.addStyleName("external");
        return anchor;
    }

    protected FeaturedWhirledPanel _featuredWhirled;
    protected AvatarPanel _avatarPanel;

    /** Our screenshot images. */
    protected static LandingImages _images = (LandingImages)GWT.create(LandingImages.class);
}
