package me.reddev.osucelebrity.twitch;

import com.google.common.collect.ImmutableMap;
import me.reddev.osucelebrity.osu.OsuStatus.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import me.reddev.osucelebrity.AbstractJDOTest;
import me.reddev.osucelebrity.OsuResponses;
import me.reddev.osucelebrity.UserException;
import me.reddev.osucelebrity.core.QueuedPlayer;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import me.reddev.osucelebrity.core.Spectator;
import me.reddev.osucelebrity.core.Trust;
import me.reddev.osucelebrity.core.VoteType;
import me.reddev.osucelebrity.osu.Osu;
import me.reddev.osucelebrity.osu.OsuStatus;
import me.reddev.osucelebrity.osu.OsuUser;
import me.reddev.osucelebrity.twitchapi.TwitchApi;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;
import org.pircbotx.Channel;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.managers.ListenerManager;
import org.pircbotx.output.OutputChannel;
import org.pircbotx.output.OutputUser;
import org.tillerino.osuApiModel.GameModes;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;

import javax.jdo.PersistenceManager;


public class TwitchIrcBotTest extends AbstractJDOTest {
  @Mock
  TwitchIrcSettings settings;
  @Mock
  Spectator spectator;

  @Mock
  User user;
  @Mock
  OutputUser outputUser;
  @Mock
  Channel channel;
  @Mock
  OutputChannel outputChannel;
  @Mock
  Osu osu;
  @Mock
  PircBotX bot;
  @Mock
  Configuration configuration;
  @Mock
  ListenerManager listenerManager;
  @Mock
  TwitchApi twitchApi;
  @Mock
  Trust trust;
  @Mock
  Twitch twitch;

  @Spy
  TwitchWhisperBot whisperBot = new TwitchWhisperBot(null) {
    @Override
    public void whisper(String username, String message) {
      // do Nothing
    }
  };

  TwitchIrcBot ircBot;

  @Before
  public void initMocks() throws Exception {
    when(bot.getConfiguration()).thenReturn(configuration);
    when(configuration.getListenerManager()).thenReturn(listenerManager);
    when(user.getNick()).thenReturn("twitchIrcUser");
    when(user.send()).thenReturn(outputUser);
    when(channel.send()).thenReturn(outputChannel);
    when(spectator.getCurrentPlayer(any())).thenReturn(
        getUser(pmf.getPersistenceManagerProxy(), "testplayer"));

    when(settings.getTwitchIrcCommand()).thenReturn("!");
    when(settings.getTwitchIrcUsername()).thenReturn("OsuCeleb");
    when(settings.getTwitchIrcHost()).thenReturn("irc.host");
    when(settings.getTwitchIrcPort()).thenReturn(420);
    when(settings.getTwitchIrcChannel()).thenReturn("channer");
    
    ircBot =
        new TwitchIrcBot(settings, osuApi, twitchApi, osu, pmf, spectator, clock,
            whisperBot, trust, twitch) {
      @Override
      public void sendMessage(String message) {
        System.out.println(message);
      }
    };
  }

  QueuedPlayer getUser(PersistenceManager pm, String playerName) throws IOException {
    OsuUser user = osuApi.getUser(playerName, pm, 0);
    return new QueuedPlayer(user, null, clock.getTime());
  }
  
  @Test
  public void testCreateBot() throws Exception {
    ircBot.getConfiguration();
  }

  @Test
  public void testQueue() throws Exception {
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!spec someone", ImmutableMap.of()));

    verify(spectator).performEnqueue(
        any(),
        eq(new QueuedPlayer(osuApi.getUser("someone", pmf.getPersistenceManagerProxy(), 0),
            QueueSource.TWITCH, 0)), eq("twitch:twitchIrcUser"), any(), any(), any());
  }

  @Test
  public void testQueueUntrusted() throws Exception {
    doThrow(new UserException("")).when(trust).checkTrust(any(), any());
    
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!spec someone", ImmutableMap.of()));

    verifyZeroInteractions(spectator);
  }

  @Test
  public void testQueueWithComment() throws Exception {
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user,
        "!spectate Q__Q    : best emoticon", ImmutableMap.of()));

    verify(spectator).performEnqueue(
        any(),
        eq(new QueuedPlayer(osuApi.getUser("Q__Q", pmf.getPersistenceManagerProxy(), 0),
            QueueSource.TWITCH, 0)), eq("twitch:twitchIrcUser"), any(), any(), any());
  }

  @Test
  public void testQueueAlias() throws Exception {
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!vote someone", ImmutableMap.of()));

    verify(spectator).performEnqueue(
        any(),
        eq(new QueuedPlayer(osuApi.getUser("someone", pmf.getPersistenceManagerProxy(), 0),
            QueueSource.TWITCH, 0)), eq("twitch:twitchIrcUser"), any(), any(), any());
  }

  @Test
  public void testDank() throws Exception {
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!dank", ImmutableMap.of()));

    verify(spectator).vote(any(), eq("twitchIrcUser"), eq(VoteType.UP), eq("!dank"));
  }

  @Test
  public void testDankUntrusted() throws Exception {
    doThrow(new UserException("")).when(trust).checkTrust(any(), any());
    
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!dank", ImmutableMap.of()));

    verifyZeroInteractions(spectator);
  }

  @Test
  public void testSkip() throws Exception {
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!skip", ImmutableMap.of()));

    verify(spectator).vote(any(), eq("twitchIrcUser"), eq(VoteType.DOWN), eq("!skip"));
  }

  @Test
  public void testForceSkip() throws Exception {
    when(spectator.advanceConditional(any(), any())).thenReturn(true);
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!forceskip x", ImmutableMap.of()));

    verify(spectator).advanceConditional(any(), eq("x"));
  }

  @Test
  public void testForceSkipUnauthorized() throws Exception {
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!forceskip x", ImmutableMap.of()));

    verifyNoMoreInteractions(spectator);
  }

  @Test
  public void testForceSpec() throws Exception {
    when(spectator.promote(any(), any())).thenReturn(true);
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!forcespec x", ImmutableMap.of()));

    verify(spectator).promote(any(), eq(osuApi.getUser("x", pmf.getPersistenceManagerProxy(), 0)));
  }

  @Test
  public void testNowPlaying() throws Exception {
    when(osu.getClientStatus()).thenReturn(
        new OsuStatus(OsuStatus.Type.PLAYING, "Hatsune Miku - Senbonzakura (Short Ver.) [Rin]"));

    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!np", ImmutableMap.of()));

    verify(outputChannel).message(any());
  }

  @Test
  public void testNowPlayingOnlyWatching() throws Exception {
    when(osu.getClientStatus()).thenReturn(new OsuStatus(OsuStatus.Type.WATCHING, "SomePlayer"));

    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!np", ImmutableMap.of()));

    verify(outputChannel, never()).message(any());
  }

  @Test
  public void testNotNowPlaying() throws Exception {
    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.CLOSED, null));
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!np", ImmutableMap.of()));

    verify(outputChannel, never()).message(any());
  }

  @Test
  public void testFixClient() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!fix", ImmutableMap.of()));

    verify(osu).restartClient();
  }

  @Test
  public void testBoost() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!boost boosttarget", ImmutableMap.of()));

    verify(spectator).boost(any(),
        eq(osuApi.getUser("boosttarget", pmf.getPersistenceManager(), 0)));
  }

  @Test
  public void testTimeout() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!timeout 60 timeouttarget", ImmutableMap.of()));

    OsuUser target = osuApi.getUser("timeouttarget", pmf.getPersistenceManager(), 0);

    assertEquals(60 * 60 * 1000, target.getTimeOutUntil());
  }

  @Test
  public void testBanMap() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!banmaps ban this", ImmutableMap.of()));

    verify(spectator).addBannedMapFilter(any(), eq("ban this"));
  }

  @Test
  public void testChangeGameMode() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);

    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!gamemode taiko player", ImmutableMap.of()));
    OsuUser player = osuApi.getUser("player", pmf.getPersistenceManager(), 0);
    assertEquals(GameModes.TAIKO, player.getGameMode());

    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!gamemode ctb player", ImmutableMap.of()));
    player = osuApi.getUser("player", pmf.getPersistenceManager(), 0);
    assertEquals(GameModes.CTB, player.getGameMode());

    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!gamemode mania player", ImmutableMap.of()));
    player = osuApi.getUser("player", pmf.getPersistenceManager(), 0);
    assertEquals(GameModes.MANIA, player.getGameMode());

    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!gamemode osu player", ImmutableMap.of()));
    player = osuApi.getUser("player", pmf.getPersistenceManager(), 0);
    assertEquals(GameModes.OSU, player.getGameMode());
  }

  @Test
  public void testExtend() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!extend someplayer", ImmutableMap.of()));

    verify(spectator).extendConditional(any(), eq("someplayer"));
  }

  @Test
  public void testFreeze() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!freeze", ImmutableMap.of()));

    verify(spectator).setFrozen(true);
  }

  @Test
  public void testUnfreeze() throws Exception {
    when(twitchApi.isModerator(user.getNick())).thenReturn(true);
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!unfreeze", ImmutableMap.of()));

    verify(spectator).setFrozen(false);
  }
  
  @Test
  public void testLink() throws Exception {
    // prepare the object and make Twitch return the object
    TwitchUser userObject = new TwitchUser(null);
    assertNull(userObject.getLinkString());
    when(twitch.getUser(any(), eq("twitchIrcUser"), anyLong(), anyBoolean())).thenReturn(userObject);

    // invoke command
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!link", ImmutableMap.of()));

    // verify that a link string has been set and that it was whispered to the user
    assertNotNull(userObject.getLinkString());
    verify(whisperBot).whisper(eq("twitchIrcUser"), contains(userObject.getLinkString()));
  }
  
  @Test
  public void testLinked() throws Exception {
    // prepare the object, link it to an osu! account and make Twitch return the object
    TwitchUser userObject = new TwitchUser(null);
    userObject.setOsuUser(osuUser);
    when(twitch.getUser(any(), eq("twitchIrcUser"), anyLong(), anyBoolean())).thenReturn(userObject);

    // invoke command
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!link", ImmutableMap.of()));

    // verify that no link string has been set
    assertNull(userObject.getLinkString());
  }
  
  @Test
  public void testPosition() throws Exception {
    when(spectator.getQueuePosition(any(), eq(osuUser))).thenReturn(420);
    
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!position "
        + osuUser.getUserName(), ImmutableMap.of()));

    verify(outputChannel).message(String.format(OsuResponses.POSITION, osuUser.getUserName(), 420));
  }
  
  @Test
  public void testPositionNotInQueue() throws Exception {
    when(spectator.getQueuePosition(any(), eq(osuUser))).thenReturn(-1);
    
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!position "
        + osuUser.getUserName(), ImmutableMap.of()));

    verify(whisperBot).whisper("twitchIrcUser", String.format(OsuResponses.NOT_IN_QUEUE, osuUser.getUserName()));
  }
  
  @Test
  public void testReplayCurrent() throws Exception {
    QueuedPlayer currentPlayer = getUser(pm, "currentPlayer");
    
    when(spectator.getCurrentPlayer(any())).thenReturn(currentPlayer);
    when(twitchApi.getReplayLink(currentPlayer)).thenReturn(new URL("http://rightthere"));
    
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!replay", ImmutableMap.of()));

    verify(outputChannel).message(contains("rightthere"));
    verify(outputChannel).message(contains("currentPlayer"));
  }
  
  @Test
  public void testReplaySpecific() throws Exception {
    QueuedPlayer specificPlayer = getUser(pm, "specificPlayer");
    specificPlayer.setState(-1);
    pm.makePersistent(specificPlayer);
    
    when(twitchApi.getReplayLink(specificPlayer)).thenReturn(new URL("http://rightthere"));
    
    ircBot.onMessage(new MessageEvent(bot, channel, settings.getTwitchIrcChannel(), user, user, "!replay specificPlayer", ImmutableMap.of()));

    verify(outputChannel).message(contains("rightthere"));
    verify(outputChannel).message(contains("specificPlayer"));
  }
}
