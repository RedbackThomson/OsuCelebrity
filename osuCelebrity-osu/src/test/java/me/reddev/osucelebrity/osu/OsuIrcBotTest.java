package me.reddev.osucelebrity.osu;

import me.reddev.osucelebrity.twitch.Twitch;
import org.junit.After;
import me.reddev.osucelebrity.OsuResponses;
import me.reddev.osucelebrity.twitch.QTwitchUser;
import me.reddev.osucelebrity.JdoQueryUtil;
import me.reddev.osucelebrity.twitch.TwitchUser;
import me.reddev.osucelebrity.twitchapi.TwitchApiUser;
import org.tillerino.osuApiModel.OsuApiUser;
import org.pircbotx.output.OutputIRC;
import me.reddev.osucelebrity.osu.Osu.PollStatusConsumer;
import me.reddev.osucelebrity.osu.PlayerStatus.PlayerStatusType;
import org.tillerino.osuApiModel.GameModes;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import org.pircbotx.snapshot.UserSnapshot;
import org.pircbotx.snapshot.UserChannelDaoSnapshot;
import org.pircbotx.hooks.events.QuitEvent;
import org.pircbotx.hooks.events.JoinEvent;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

import org.pircbotx.hooks.events.ServerResponseEvent;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import me.reddev.osucelebrity.AbstractJDOTest;
import me.reddev.osucelebrity.Privilege;
import me.reddev.osucelebrity.Responses;
import me.reddev.osucelebrity.core.EnqueueResult;
import me.reddev.osucelebrity.core.QueuedPlayer;
import me.reddev.osucelebrity.core.Spectator;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.pircbotx.Channel;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.managers.ListenerManager;
import org.pircbotx.output.OutputUser;


public class OsuIrcBotTest extends AbstractJDOTest {
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
  Configuration configuration;
  @Mock
  ListenerManager listenerManager;
  @Mock
  OsuIrcSettings settings;
  @Mock
  OutputIRC ourputIrc;
  @Mock
  Osu osu;
  @Mock
  Twitch twitch;
  
  OsuIrcBot ircBot;
  
  OsuUser osuIrcUser;

  @Before
  public void initMocks() throws Exception {
    when(bot.getConfiguration()).thenReturn(configuration);
    when(configuration.getListenerManager()).thenReturn(listenerManager);
    when(user.getNick()).thenReturn("osuIrcUser");

    osuIrcUser = osuApi.getUser(user.getNick(), pm, 0);

    when(settings.getOsuIrcCommand()).thenReturn("!");
    when(settings.getOsuCommandUser()).thenReturn("BanchoBot");
    when(settings.getOsuIrcUsername()).thenReturn("OsuCeleb");
    when(settings.getOsuIrcHost()).thenReturn("server.address");
    when(settings.getOsuIrcPort()).thenReturn(420);
    when(settings.getOsuIrcAutoJoin()).thenReturn("#somechannel");
    when(user.send()).thenReturn(outputUser);
    
    doAnswer(x -> {
      System.out.println(x.getArgumentAt(0, String.class));
      return null;
    }).when(outputUser).message(anyString());
    
    when(bot.sendIRC()).thenReturn(ourputIrc);

    ircBot = new OsuIrcBot(osu, osuApi, settings, pmf, spectator, clock, twitch, new Pinger() {
      @Override
      void ping(PircBotX bot) throws IOException, InterruptedException {
        // do nothing
      }
    }) {
      @Override
      public PircBotX getBot() {
        return bot;
      }
    };
  }
  
  @Test
  public void testConfigure() throws Exception {
    ircBot.getConfiguration();
  }

  @Test
  public void testSelfQueue() throws Exception {
    when(spectator.enqueue(any(), any(), eq(true), any(), eq(true))).thenReturn(EnqueueResult.SUCCESS);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!spec"));

    ArgumentCaptor<QueuedPlayer> captor = ArgumentCaptor.forClass(QueuedPlayer.class);

    verify(spectator, only()).enqueue(any(), captor.capture(), eq(true),
        eq("osu:" + osuApi.getUser("osuIrcUser", pm, 0L).getUserId()), eq(true));

    QueuedPlayer request = captor.getValue();
    assertEquals("osuIrcUser", request.getPlayer().getUserName());
    assertEquals(true, request.isNotify());

    verify(outputUser, only()).message(Responses.SELF_QUEUE_SUCCESSFUL);
  }

  @Test
  public void testSelfQueueFail() throws Exception {
    when(spectator.enqueue(any(), any(), eq(true), any(), eq(true))).thenReturn(
        EnqueueResult.FAILURE);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!spec"));

    verify(spectator, only()).enqueue(any(), any(), eq(true),
        eq("osu:" + osuApi.getUser("osuIrcUser", pm, 0L).getUserId()), eq(true));

    verify(outputUser, only()).message(EnqueueResult.FAILURE.formatResponse("osuIrcUser"));
  }

  @Test
  public void testSkip() throws Exception {
    osuApi.getUser("osuIrcUser", pm, 0).setPrivilege(Privilege.MOD);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!forceskip x"));

    verify(spectator, only()).advanceConditional(any(),
        eq("x"));

    verify(outputUser, only()).message(any());
  }

  @Test
  public void testSpecSilent() throws Exception {
    osuApi.getUser("osuIrcUser", pm, 0).setPrivilege(Privilege.MOD);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!specsilent x"));

    verify(spectator, only()).performEnqueue(any(),
        eq(new QueuedPlayer(osuApi.getUser("x", pm, 0L), QueueSource.AUTO, 0L)), eq(null), any(),
        any(), any());
  }

  @Test
  public void testSkipUnauthorized() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!forceskip x"));

    verifyZeroInteractions(spectator);

    verify(outputUser, only()).message(any());
  }

  @Test
  public void testMuting() throws Exception {
    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsNotifications());

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!mute"));

    assertFalse(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .isAllowsNotifications());

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!unmute"));

    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsNotifications());
  }

  @Test
  public void testOpting() throws Exception {
    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsSpectating());

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!optout"));

    assertFalse(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsSpectating());
    verify(spectator).removeFromQueue(any(),
        eq(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)));

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!optin"));

    assertTrue(osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0).isAllowsSpectating());
  }

  @Test
  public void testUserNamesParser() throws Exception {
    ircBot.onServerResponse(new ServerResponseEvent(bot, 353,
        ":irc.server.net 353 Phyre = #SomeChannel :@me +you them", ImmutableList
            .copyOf(new String[] {"#SomeChannel", "+me @you them"})));

    assertEquals(new HashSet<>(Arrays.asList("me", "you", "them")), ircBot.getOnlineUsers());
  }
  
  @Test
  public void testJoinQuit() throws Exception {
    ircBot.onJoin(new JoinEvent(bot, channel, user, user));

    assertTrue(ircBot.getOnlineUsers().contains("osuIrcUser"));

    ircBot.onQuit(new QuitEvent(bot, new UserChannelDaoSnapshot(bot, null, null, null,
        null, null, null), user, new UserSnapshot(user), "no reason"));

    assertFalse(ircBot.getOnlineUsers().contains("osuIrcUser"));
  }

  @Test
  public void testQueue() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!spec thatguy"));

    OsuUser requestedUser = osuApi.getUser("thatguy", pmf.getPersistenceManager(), 0);

    verify(spectator).performEnqueue(any(),
        eq(new QueuedPlayer(requestedUser, QueueSource.OSU, 0)),
        eq("osu:" + osuIrcUser.getUserId()), any(), any(), any());
  }
  
  @Test
  public void testQueueWithComment() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!spec FrenzyLi: because I want to"));

    OsuUser requestedUser = osuApi.getUser("FrenzyLi", pmf.getPersistenceManager(), 0);

    verify(spectator).performEnqueue(any(),
        eq(new QueuedPlayer(requestedUser, QueueSource.OSU, 0)),
        eq("osu:" + osuIrcUser.getUserId()), any(), any(), any());
  }

  @Test
  public void testQueueAlias() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!vote thatguy"));

    OsuUser requestedUser = osuApi.getUser("thatguy", pmf.getPersistenceManager(), 0);

    verify(spectator).performEnqueue(any(),
        eq(new QueuedPlayer(requestedUser, QueueSource.OSU, 0)),
        eq("osu:" + osuIrcUser.getUserId()), any(), any(), any());
  }
  
  @Test
  public void testForceSpec() throws Exception {
    osuApi.getUser("osuIrcUser", pm, 0).setPrivilege(Privilege.MOD);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!forcespec x"));
    
    verify(spectator).promote(any(), 
        eq(osuApi.getUser("x", pmf.getPersistenceManagerProxy(), 0)));
  }
  
  @Test
  public void testForceSpecUnauthorized() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!forcespec x"));
    
    verify(spectator, times(0)).promote(any(), 
        eq(osuApi.getUser("x", pmf.getPersistenceManagerProxy(), 0)));
  }
  
  @Test
  public void testFixClient() throws Exception {
    osuApi.getUser("osuIrcUser", pm, 0).setPrivilege(Privilege.MOD);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!fix"));
    
    verify(osu).restartClient();
  }
  
  @Test
  public void testFixClientUnauthorized() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!fix"));
    
    verify(osu, times(0)).restartClient();
  }
  
  @Test
  public void testGameMode() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!gamemode ctb"));

    assertEquals(GameModes.CTB, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!gamemode taiko"));

    assertEquals(GameModes.TAIKO, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!gamemode mania"));

    assertEquals(GameModes.MANIA, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!gamemode osu"));

    assertEquals(GameModes.OSU, osuApi.getUser("osuIrcUser", pmf.getPersistenceManager(), 0)
        .getGameMode());
  }

  @Test
  public void testParseStatus() throws Exception {
    OsuUser tillerino = osuApi.getUser("Tillerino", pm, 0);
    OsuUser thelewa = osuApi.getUser("thelewa", pm, 0);
    OsuUser agus2001 = osuApi.getUser("agus2001", pm, 0);
    clock.sleepUntil(2);
    
    assertEquals(new PlayerStatus(thelewa, PlayerStatusType.OFFLINE, 2),
        ircBot.parseStatus(pm, "Stats for (thelewa)[https://osu.ppy.sh/u/" + thelewa.getUserId() + "]:").get());

    assertEquals(new PlayerStatus(agus2001, PlayerStatusType.AFK, 2),
        ircBot.parseStatus(pm, "Stats for (agus2001)[https://osu.ppy.sh/u/" + agus2001.getUserId() + "] is Afk:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.IDLE, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/" + tillerino.getUserId() + "] is Idle:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.MODDING, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/" + tillerino.getUserId() + "] is Modding:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.PLAYING, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/" + tillerino.getUserId() + "] is Playing:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.WATCHING, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/" + tillerino.getUserId() + "] is Watching:").get());

    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.MULTIPLAYING, 2),
        ircBot.parseStatus(pm, "Stats for (Tillerino)[https://osu.ppy.sh/u/" + tillerino.getUserId() + "] is Multiplaying:").get());
  }
  
  @Test
  public void testBanchoBotStatus() throws Exception {
    OsuUser tillerino = osuApi.getUser("Tillerino", pm, 0);

    String osuCommandUser = settings.getOsuCommandUser();
    when(user.getNick()).thenReturn(osuCommandUser);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "Stats for (Tillerino)[https://osu.ppy.sh/u/" + tillerino.getUserId() + "] is Playing:"));

    verify(spectator).reportStatus(any(),
        eq(new PlayerStatus(tillerino, PlayerStatusType.PLAYING, 0)));
  }
  
  @Test
  public void testMod() throws Exception {
    OsuUser admin = osuApi.getUser("admin", pm, 0);
    admin.setPrivilege(Privilege.ADMIN);
    {
      OsuUser mod = osuApi.getUser("newmod", pm, 0);
    }
    
    when(user.getNick()).thenReturn("admin");
    
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!mod newmod"));
    
    OsuUser mod = osuApi.getUser("newmod", pmf.getPersistenceManager(), 0);
    assertEquals(Privilege.MOD, mod.getPrivilege());
  }
  
  @Test
  public void testModFail() throws Exception {
    OsuUser admin = osuApi.getUser("notadmin", pm, 0);
    {
      OsuUser mod = osuApi.getUser("newmod", pm, 0);
    }
    
    when(user.getNick()).thenReturn("notadmin");
    
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!mod newmod"));
    
    OsuUser mod = osuApi.getUser("newmod", pmf.getPersistenceManager(), 0);
    assertEquals(Privilege.PLAYER, mod.getPrivilege());
  }
  
  @Test
  public void testBanchoBotStatusHandler() throws Exception {
    OsuUser tillerino = osuApi.getUser("Tillerino", pm, 0);

    PollStatusConsumer consumer = mock(PollStatusConsumer.class);
    ircBot.pollIngameStatus(tillerino, consumer);
    
    assertSame(consumer, ircBot.statusConsumers.get(tillerino.getUserId()).peek().getValue());
    
    String osuCommandUser = settings.getOsuCommandUser();
    when(user.getNick()).thenReturn(osuCommandUser);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "Stats for (Tillerino)[https://osu.ppy.sh/u/" + tillerino.getUserId() + "] is Playing:"));

    verifyNoMoreInteractions(spectator);
    verify(consumer).accept(any(), any());
    
    assertTrue(ircBot.statusConsumers.get(tillerino.getUserId()).isEmpty());
  }
  
  @Test
  public void testLink() throws Exception {
    {
      TwitchApiUser twitchApiUser = new TwitchApiUser();
      TwitchUser twitchUser = new TwitchUser(twitchApiUser);
      twitchUser.setLinkString("<LINKSTRING>");
      
      pm.makePersistent(twitchUser);
    }
    
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!link <LINKSTRING>"));

    // there is only on twitch user, so we can grab anything.
    TwitchUser twitchUser =
        JdoQueryUtil.getUnique(pmf.getPersistenceManager(), QTwitchUser.twitchUser,
            QTwitchUser.twitchUser.user.id.eq(0)).get();
    assertEquals(osuIrcUser, twitchUser.getOsuUser());
    assertNull(twitchUser.getLinkString());
  }
  
  @Test
  public void testLinkNotFound() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!link <LINKSTRING>"));

    verify(outputUser).message(OsuResponses.UNKNOWN_LINK);
  }
  
  @Test
  public void testLinkDuplication() throws Exception {
    {
      // create request
      TwitchApiUser twitchApiUser = new TwitchApiUser();
      TwitchUser twitchUser = new TwitchUser(twitchApiUser);
      twitchUser.setLinkString("<LINKSTRING>");
      
      pm.makePersistent(twitchUser);
    }
    
    {
      // create linked account
      TwitchApiUser twitchApiUser = new TwitchApiUser();
      twitchApiUser.setId(1);
      TwitchUser twitchUser = new TwitchUser(twitchApiUser);
      twitchUser.setOsuUser(osuIrcUser);
      
      pm.makePersistent(twitchUser);
    }
    
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!link <LINKSTRING>"));

    verify(outputUser).message(OsuResponses.ALREADY_LINKED);
  }
  
  @Test
  public void testPosition() throws Exception {
    when(spectator.getQueuePosition(any(), eq(osuUser))).thenReturn(69);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!position " + osuUser.getUserName()));
   
    verify(outputUser).message(String.format(OsuResponses.POSITION, "defaultUser", 69));
  }
  
  @Test
  public void testPositionNotInQueue() throws Exception {
    when(spectator.getQueuePosition(any(), eq(osuUser))).thenReturn(-1);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!position " + osuUser.getUserName()));
   
    verify(outputUser).message(String.format(OsuResponses.NOT_IN_QUEUE, "defaultUser"));
  }
  
  @Test
  public void testSelfPosition() throws Exception {
    when(spectator.getQueuePosition(any(), eq(osuApi.getUser("osuIrcUser", pm, 0L)))).thenReturn(420);
    
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!position"));
   
    verify(outputUser).message(String.format(OsuResponses.POSITION, "osuIrcUser", 420));
  }
  
  @Test
  public void testSelfPositionNotInQueue() throws Exception {
    when(spectator.getQueuePosition(any(), eq(osuApi.getUser("osuIrcUser", pm, 0L)))).thenReturn(-1);
    
    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user,
        "!position"));
   
    verify(outputUser).message(String.format(OsuResponses.NOT_IN_QUEUE, "osuIrcUser"));
  }
  
  @Test
  public void testTimedOut() throws Exception {
    osuApi.getUser("osuIrcUser", pm, 0L).setTimeOutUntil(1L);

    ircBot.onPrivateMessage(new PrivateMessageEvent(bot, user, user, "!position"));

    verify(outputUser).message(OsuResponses.TIMED_OUT_CURRENTLY);
  }
}
