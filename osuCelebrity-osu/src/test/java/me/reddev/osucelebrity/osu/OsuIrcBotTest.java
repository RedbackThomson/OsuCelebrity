package me.reddev.osucelebrity.osu;

import me.reddev.osucelebrity.osu.PlayerStatus.PlayerStatusType;
import org.tillerino.osuApiModel.GameModes;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.snapshot.UserSnapshot;
import org.pircbotx.snapshot.UserChannelDaoSnapshot;
import org.pircbotx.hooks.events.QuitEvent;
import org.pircbotx.hooks.events.JoinEvent;

import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.HashSet;

import org.pircbotx.hooks.events.ServerResponseEvent;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import javax.jdo.PersistenceManager;

import me.reddev.osucelebrity.AbstractJDOTest;
import me.reddev.osucelebrity.Privilege;
import me.reddev.osucelebrity.Responses;
import me.reddev.osucelebrity.core.EnqueueResult;
import me.reddev.osucelebrity.core.MockClock;
import me.reddev.osucelebrity.core.QueuedPlayer;
import me.reddev.osucelebrity.core.Spectator;
import me.reddev.osucelebrity.osuapi.MockOsuApi;
import me.reddev.osucelebrity.osuapi.OsuApi;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.pircbotx.Channel;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.managers.ListenerManager;
import org.pircbotx.output.OutputUser;


public class OsuIrcBotTest extends AbstractJDOTest {
  MockClock clock = new MockClock();
  OsuApi osuApi = new MockOsuApi();

  @Mock
  Spectator spectator;

  @Mock
  User user;
  @Mock
  OutputUser outputUser;
  @Mock
  Channel channel;
  @Mock
  PircBotX bot;
  @Mock
  Configuration<PircBotX> configuration;
  @Mock
  ListenerManager<PircBotX> listenerManager;
  @Mock
  OsuIrcSettings settings;

  OsuIrcBot ircBot;

  @Before
  public void initMocks() {
    MockitoAnnotations.initMocks(this);

    when(bot.getConfiguration()).thenReturn(configuration);
    when(configuration.getListenerManager()).thenReturn(listenerManager);
    when(user.getNick()).thenReturn("osuIrcUser");

    when(settings.getOsuIrcCommand()).thenReturn("!");
    when(settings.getOsuCommandUser()).thenReturn("BanchoBot");
    when(user.send()).thenReturn(outputUser);

    ircBot = new OsuIrcBot(null, osuApi, settings, pmf, spectator, clock);
  }

  @Test
  public void testSelfQueue() throws Exception {
    when(spectator.enqueue(any(), any(), eq(true))).thenReturn(EnqueueResult.SUCCESS);

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!spec"));

    ArgumentCaptor<QueuedPlayer> captor = ArgumentCaptor.forClass(QueuedPlayer.class);

    verify(spectator, only()).enqueue(any(), captor.capture(), eq(true));

    QueuedPlayer request = captor.getValue();
    assertEquals("osuIrcUser", request.getPlayer().getUserName());
    assertEquals(true, request.isNotify());

    verify(outputUser, only()).message(Responses.SELF_QUEUE_SUCCESSFUL);
  }

  @Test
  public void testSkip() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    osuApi.getUser("osuIrcUser", pm, 0).setPrivilege(Privilege.MOD);

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!forceskip x"));

    verify(spectator, only()).advanceConditional(any(),
        eq("x"));

    verify(outputUser, only()).message(any());
  }

  @Test
  public void testSkipUnauthorized() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!forceskip x"));

    verifyZeroInteractions(spectator);

    verify(outputUser, only()).message(any());
  }

  @Test
  public void testMuting() throws Exception {
    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsNotifications());

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!mute"));

    assertFalse(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .isAllowsNotifications());

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!unmute"));

    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsNotifications());
  }

  @Test
  public void testOpting() throws Exception {
    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsSpectating());

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!optout"));

    assertFalse(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsSpectating());
    verify(spectator).removeFromQueue(any(),
        eq(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)));

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!optin"));

    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsSpectating());
  }

  @Test
  public void testUserNamesParser() throws Exception {
    ircBot.onServerResponse(new ServerResponseEvent<PircBotX>(bot, 353,
        ":irc.server.net 353 Phyre = #SomeChannel :@me +you them", ImmutableList
            .copyOf(new String[] {"#SomeChannel", "+me @you them"})));

    assertEquals(new HashSet<>(Arrays.asList("me", "you", "them")), ircBot.getOnlineUsers());
  }
  
  @Test
  public void testOfflineUser() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    ircBot.onServerResponse(new ServerResponseEvent<PircBotX>(bot, 401,
        ":cho.ppy.sh 401 OsuCelebrity doesnotexist :No such nick", ImmutableList
            .copyOf(new String[] {"OsuCelebrity", "doesnotexist", "No such nick"})));

    verify(spectator).reportStatus(
        any(),
        eq(new PlayerStatus(osuApi.getIrcUser("doesnotexist", pm, 0).getUser(),
            PlayerStatusType.OFFLINE, 0)));
  }

  @Test
  public void testJoinQuit() throws Exception {
    ircBot.onJoin(new JoinEvent<PircBotX>(bot, channel, user));

    assertTrue(ircBot.getOnlineUsers().contains("osuIrcUser"));

    ircBot.onQuit(new QuitEvent<PircBotX>(bot, new UserChannelDaoSnapshot(bot, null, null, null,
        null, null, null), new UserSnapshot(user), "no reason"));

    assertFalse(ircBot.getOnlineUsers().contains("osuIrcUser"));
  }

  @Test
  public void testQueue() throws Exception {
    when(spectator.enqueue(any(), any(), eq(false))).thenReturn(EnqueueResult.SUCCESS);
    
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!spec thatguy"));
    
    verify(spectator, only()).enqueue(
        any(),
        eq(new QueuedPlayer(osuApi.getUser("thatguy", pmf.getPersistenceManager(), 0),
            QueueSource.OSU, 0)), eq(false));
  }
  
  @Test
  public void testForceSpec() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    
    osuApi.getUser("osuIrcUser", pm, 0).setPrivilege(Privilege.MOD);

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!forcespec x"));
    
    verify(spectator).promote(any(), 
        eq(osuApi.getUser("x", pmf.getPersistenceManagerProxy(), 0)));
  }
  
  @Test
  public void testForceSpecUnauthorized() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!forcespec x"));
    
    verify(spectator, times(0)).promote(any(), 
        eq(osuApi.getUser("x", pmf.getPersistenceManagerProxy(), 0)));
  }
  
  @Test
  public void testGameMode() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!gamemode ctb"));

    assertEquals(GameModes.CTB, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!gamemode taiko"));

    assertEquals(GameModes.TAIKO, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!gamemode mania"));

    assertEquals(GameModes.MANIA, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!gamemode osu"));

    assertEquals(GameModes.OSU, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
  }

  @Test
  public void testParseStatus() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser tillerino = osuApi.getUser("Tillerino", pm, 0);
    OsuUser thelewa = osuApi.getUser("thelewa", pm, 0);
    OsuUser agus2001 = osuApi.getUser("agus2001", pm, 0);
    clock.sleepUntil(2);
    
    assertEquals(new PlayerStatus(thelewa, PlayerStatusType.OFFLINE, 2),
        ircBot.parseStatus(pm, "Stats for (thelewa)[https://osu.ppy.sh/u/1]:").get());

    assertEquals(new PlayerStatus(agus2001, PlayerStatusType.AFK, 2),
        ircBot.parseStatus(pm, "Stats for (agus2001)[https://osu.ppy.sh/u/2] is Afk:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.IDLE, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/0] is Idle:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.MODDING, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/0] is Modding:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.PLAYING, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/0] is Playing:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.WATCHING, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/0] is Watching:").get());
  }
  
  @Test
  public void testBanchoBotStatus() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser tillerino = osuApi.getUser("Tillerino", pm, 0);

    String osuCommandUser = settings.getOsuCommandUser();
    when(user.getNick()).thenReturn(osuCommandUser);

    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user,
        "Stats for (Tillerino)[https://osu.ppy.sh/u/0] is Playing:"));

    verify(spectator).reportStatus(any(),
        eq(new PlayerStatus(tillerino, PlayerStatusType.PLAYING, 0)));
  }
}
