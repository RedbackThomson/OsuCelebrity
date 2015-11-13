package me.reddev.osucelebrity.osu;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import javax.jdo.PersistenceManager;

import me.reddev.osucelebrity.AbstractJDOTest;
import me.reddev.osucelebrity.Priviledge;
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
    when(user.send()).thenReturn(outputUser);

    ircBot = new OsuIrcBot(null, osuApi, settings, pmf, spectator, clock);
  }

  @Test
  public void testSelfQueue() throws Exception {
    when(spectator.enqueue(any(), any())).thenReturn(EnqueueResult.SUCCESS);
    
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!q"));

    ArgumentCaptor<QueuedPlayer> captor = ArgumentCaptor.forClass(QueuedPlayer.class);
    
    verify(spectator, only()).enqueue(any(), captor.capture());
    
    QueuedPlayer request = captor.getValue();
    assertEquals("osuIrcUser", request.getPlayer().getUserName());
    assertEquals(true, request.isNotify());
    
    verify(outputUser, only()).message(Responses.SELF_QUEUE_SUCCESSFUL);
  }

  @Test
  public void testSkip() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    osuApi.getUser("osuIrcUser", 0, pm, 0).setPriviledge(Priviledge.MOD);
    
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!skip"));

    verify(spectator, only()).advance(any());
    
    verify(outputUser, only()).message(any());
  }

  @Test
  public void testSkipUnauthorized() throws Exception {
    ircBot.onPrivateMessage(new PrivateMessageEvent<PircBotX>(bot, user, "!skip"));

    verifyZeroInteractions(spectator);
    
    verify(outputUser, only()).message(any());
  }
}
