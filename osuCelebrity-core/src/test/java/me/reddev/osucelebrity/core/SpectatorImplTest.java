package me.reddev.osucelebrity.core;

import me.reddev.osucelebrity.osu.PlayerStatus.PlayerStatusType;
import me.reddev.osucelebrity.osu.PlayerStatus;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import me.reddev.osucelebrity.osu.PlayerActivity;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import me.reddev.osucelebrity.AbstractJDOTest;
import me.reddev.osucelebrity.osu.Osu;
import me.reddev.osucelebrity.osu.OsuStatus;
import me.reddev.osucelebrity.osu.OsuStatus.Type;
import me.reddev.osucelebrity.osu.OsuUser;
import me.reddev.osucelebrity.osuapi.ApiUser;
import me.reddev.osucelebrity.osuapi.MockOsuApi;
import me.reddev.osucelebrity.osuapi.OsuApi;
import me.reddev.osucelebrity.twitch.Twitch;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tillerino.osuApiModel.GameModes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.jdo.PersistenceManager;


public class SpectatorImplTest extends AbstractJDOTest {
  @Mock
  private Twitch twitch;
  @Mock
  private Osu osu;
  @Mock
  private CoreSettings settings;
  @Mock
  private ExecutorService exec;

  Clock clock = new MockClock();
  OsuApi api = new MockOsuApi();
  
  private SpectatorImpl spectator;

  @Before
  public void initMocks() throws IOException {
    MockitoAnnotations.initMocks(this);

    when(settings.getDefaultSpecDuration()).thenReturn(30000l);
    when(settings.getNextPlayerNotifyTime()).thenReturn(10000l);
    when(settings.getVoteWindow()).thenReturn(30000l);
    when(settings.getOfflineTimeout()).thenReturn(5000L);
    when(settings.getIdleTimeout()).thenReturn(15000L);
    when(settings.getAutoSpecTime()).thenReturn(300000L);
    when(settings.getAutoSpecMaxRank()).thenReturn(1000L);
    when(settings.getAutoSpecMaxLastActivity()).thenReturn(300000L);
    when(settings.getMinPlayCount()).thenReturn(1000);
    when(settings.getMaxLastActivity()).thenReturn(24L * 60 * 60 * 1000);
    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.PLAYING, ""));
    
    when(exec.submit(any(Runnable.class))).thenAnswer(arg -> {
      ((Runnable) arg.getArguments()[0]).run();
      return null;
    });
    
    spectator = new SpectatorImpl(twitch, clock, osu, settings, pmf, api, exec);
  }

  QueuedPlayer getUser(PersistenceManager pm, String playerName) throws IOException {
    OsuUser user = api.getUser(playerName, pm, 0);
    return new QueuedPlayer(user, null, clock.getTime());
  }

  @Test
  public void testEnqueue() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();

    assertFalse(spectator.advance(pm, PlayerQueue.loadQueue(pm, clock)));

    QueuedPlayer user = getUser(pm, "someplayer");
    assertEquals(EnqueueResult.SUCCESS, spectator.enqueue(pm, user, false, "twitchuser"));
    // already in queue
    assertEquals(EnqueueResult.NEXT, spectator.enqueue(pm, user, false, "twitchuser"));

    spectator.loop(pm);
    
    QueuedPlayer user2 = getUser(pm, "someplayer2");
    assertEquals(EnqueueResult.SUCCESS, spectator.enqueue(pm, user2, false));
    assertEquals(EnqueueResult.NEXT, spectator.enqueue(pm, user2, false, "twitchuser"));

    spectator.loop(pm);
    
    QueuedPlayer user3 = getUser(pm, "someplayer3");
    assertEquals(EnqueueResult.SUCCESS, spectator.enqueue(pm, user3, false));
    assertEquals(EnqueueResult.VOTED, spectator.enqueue(pm, user3, false, "twitchuser"));
    assertEquals(EnqueueResult.NOT_VOTED, spectator.enqueue(pm, user3, false, "twitchuser"));
    assertEquals(EnqueueResult.VOTED, spectator.enqueue(pm, user3, false, "twitchuser2"));
    
    // we don't want this player to receive a queue or next message, since they are spectated instantly
    verify(osu, times(0)).notifyQueued(eq(user.getPlayer()), anyInt());
    verify(osu, times(0)).notifyNext(user.getPlayer());
    verify(osu).notifyStarting(user.getPlayer());
    
    // we don't want the second player to receive a queue message, since they are already next
    verify(osu, times(0)).notifyQueued(eq(user2.getPlayer()), anyInt());
    verify(osu).notifyNext(user2.getPlayer());
    
    verify(osu).notifyQueued(user3.getPlayer(), 2);

    verify(osu).startSpectate(user.getPlayer());
    // currently being spectated
    assertEquals(EnqueueResult.CURRENT, spectator.enqueue(pm, user, false));
  }

  @Test
  public void testTiming() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();

    spectator.loop(pm);

    QueuedPlayer user1 = getUser(pm, "someplayer");
    spectator.enqueue(pm, user1, false);
    clock.sleepUntil(1);
    QueuedPlayer user2 = getUser(pm, "someplayer2");
    spectator.enqueue(pm, user2, false);

    PlayerQueue queue = PlayerQueue.loadQueue(pm, clock);

    spectator.loop(pm);

    queue = PlayerQueue.loadQueue(pm, clock);
    assertEquals(user1, queue.currentlySpectating().get());
    assertFalse(queue.spectatingNext().isPresent());
    assertEquals(queue.spectatingUntil(), 30001);

    clock.sleepUntil(20002);
    spectator.loop(pm);

    assertEquals(user1, queue.currentlySpectating().get());
    assertEquals(user2, queue.spectatingNext().get());

    clock.sleepUntil(30002);
    spectator.loop(pm);

    assertEquals(user2, queue.currentlySpectating().get());
    assertFalse(queue.spectatingNext().isPresent());
  }

  @Test
  public void testPromote() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    spectator
        .enqueue(pm, new QueuedPlayer(api.getUser("someplayer", pm, 0), null, clock.getTime()), false);

    spectator.loop(pm);

    OsuUser user2 = api.getUser("someplayer2", pm, 0);

    spectator.promote(pm, user2);

    PlayerQueue queue = PlayerQueue.loadQueue(pm, clock);
    assertEquals(user2, queue.currentlySpectating().get().getPlayer());
  }

  @Test
  public void testPromote2() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    spectator
        .enqueue(pm, new QueuedPlayer(api.getUser("someplayer", pm, 0), null, clock.getTime()), false);

    spectator.loop(pm);
    clock.sleepUntil(1000);

    spectator
        .enqueue(pm, new QueuedPlayer(api.getUser("someplayer3", pm, 0), null, clock.getTime()), false);

    spectator.loop(pm);
    clock.sleepUntil(2000);
    
    spectator
        .enqueue(pm, new QueuedPlayer(api.getUser("someplayer2", pm, 0), null, clock.getTime()), false);

    spectator.loop(pm);
    clock.sleepUntil(3000);

    OsuUser user2 = api.getUser("someplayer2", pm, 0);

    spectator.promote(pm, user2);

    PlayerQueue queue = PlayerQueue.loadQueue(pm, clock);
    assertEquals(user2, queue.currentlySpectating().get().getPlayer());
  }

  @Test
  public void testApprovalUnique() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    spectator
        .enqueue(pm, new QueuedPlayer(api.getUser("someplayer", pm, 0), null, clock.getTime()), false);

    spectator.loop(pm);

    spectator.enqueue(pm,
        new QueuedPlayer(api.getUser("someplayer2", pm, 0), null, clock.getTime()), false);

    spectator.loop(pm);

    spectator.vote(pm, "spammer", VoteType.UP);
    spectator.vote(pm, "spammer", VoteType.UP);
    spectator.vote(pm, "spammer", VoteType.UP);
    spectator.vote(pm, "spammer", VoteType.UP);
    spectator.vote(pm, "spammer", VoteType.UP);

    spectator.vote(pm, "bummer", VoteType.DOWN);

    assertEquals(.5, spectator.getApproval(pm, spectator.getCurrentPlayer(pm)), 0d);
  }

  @Test
  public void testApprovalLast() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser user = api.getUser("someplayer", pm, 0);
    spectator.enqueue(pm, new QueuedPlayer(user, null, clock.getTime()), false);

    spectator.loop(pm);

    spectator.vote(pm, "flipflopper", VoteType.UP);
    spectator.vote(pm, "flipflopper", VoteType.DOWN);

    assertEquals(0, spectator.getApproval(pm, spectator.getCurrentPlayer(pm)), 0d);
  }

  @Test
  public void testApprovalNoVotes() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser user = api.getUser("someplayer", pm, 0);
    spectator.enqueue(pm, new QueuedPlayer(user, null, clock.getTime()), false);

    spectator.loop(pm);

    assertEquals(Double.NaN, spectator.getApproval(pm, spectator.getCurrentPlayer(pm)), 0d);
  }

  @Test
  public void testNoVotingDuringStreamDelay() throws Exception {
    when(settings.getStreamDelay()).thenReturn(10000L);

    PersistenceManager pm = pmf.getPersistenceManager();
    assertEquals(
        EnqueueResult.SUCCESS,
        spectator.enqueue(pm,
            new QueuedPlayer(api.getUser("someplayer", pm, 0), null, clock.getTime()), false));

    spectator.loop(pm);

    assertFalse(spectator.vote(pm, "me", VoteType.UP));
    
    clock.sleepUntil(settings.getStreamDelay());

    assertTrue(spectator.vote(pm, "me", VoteType.UP));
  }

  @Test
  public void testRemainingTime() throws Exception {

    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser user = api.getUser("someplayer", pm, 0);
    spectator.enqueue(pm, new QueuedPlayer(user, null, clock.getTime()), false);

    spectator.loop(pm);

    {
      QueuedPlayer currentPlayer = spectator.getCurrentPlayer(pm);
      assertEquals(user, currentPlayer.getPlayer());
      assertEquals(30000, currentPlayer.getStoppingAt());
    }

    clock.sleepUntil(10000);
    spectator.loop(pm);
    assertEquals(30000, spectator.getCurrentPlayer(pm).getStoppingAt());
    spectator.vote(pm, "me", VoteType.UP);

    clock.sleepUntil(20000);
    spectator.loop(pm);
    assertEquals(50000, spectator.getCurrentPlayer(pm).getStoppingAt());

    clock.sleepUntil(40000);
    spectator.loop(pm);
    assertEquals(70000, spectator.getCurrentPlayer(pm).getStoppingAt());

    clock.sleepUntil(60000);
    spectator.loop(pm);
    assertEquals(70000, spectator.getCurrentPlayer(pm).getStoppingAt());
  }
  
  @Test
  public void testFastDrain() throws Exception {

    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser user = api.getUser("someplayer", pm, 0);
    QueuedPlayer queued = new QueuedPlayer(user, null, clock.getTime());
    spectator.enqueue(pm, queued, false);

    spectator.loop(pm);
    
    assertEquals(30000, queued.getStoppingAt());
    
    spectator.vote(pm, "negativeNancy", VoteType.DOWN);
    
    clock.sleepUntil(10000);
    spectator.loop(pm);
    
    assertEquals(20000, queued.getStoppingAt());
  }

  @Test
  public void testTimeRefill() throws Exception {

    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser user = api.getUser("someplayer", pm, 0);
    spectator.enqueue(pm, new QueuedPlayer(user, null, clock.getTime()), false);

    spectator.loop(pm);

    {
      QueuedPlayer currentPlayer = spectator.getCurrentPlayer(pm);
      assertEquals(user, currentPlayer.getPlayer());
      assertEquals(30000, currentPlayer.getStoppingAt());
    }

    clock.sleepUntil(10000);
    spectator.loop(pm);
    // remaining time is now 20s
    spectator.vote(pm, "me", VoteType.UP);
    spectator.vote(pm, "someotherguy", VoteType.DOWN);

  }

  @Test
  public void testPlayerOffline() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();

    spectator.enqueue(pm, getUser(pm, "user1"), false);
    QueuedPlayer player2 = getUser(pm, "user2");
    spectator.enqueue(pm, player2, false);
    spectator.enqueue(pm, getUser(pm, "user3"), false);

    when(osu.getClientStatus()).thenReturn(null);
    spectator.loop(pm);

    clock.sleepUntil(1000);
    spectator.loop(pm);
    assertEquals("user1", spectator.getCurrentPlayer(pm).getPlayer().getUserName());

    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.WATCHING, ""));

    for (; clock.getTime() < 10000; clock.sleepUntil(clock.getTime() + 1000)) {
      spectator.loop(pm);
      assertEquals("user1", spectator.getCurrentPlayer(pm).getPlayer().getUserName());
    }

    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.PLAYING, ""));

    for (; clock.getTime() < settings.getDefaultSpecDuration() + 9000; clock
        .sleepUntil(clock.getTime() + 1000)) {
      spectator.loop(pm);
      assertEquals("user1", spectator.getCurrentPlayer(pm).getPlayer().getUserName());
    }

    spectator.loop(pm);
    assertEquals("user2", spectator.getCurrentPlayer(pm).getPlayer().getUserName());

    when(osu.getClientStatus()).thenReturn(null);

    for (; clock.getTime() < settings.getDefaultSpecDuration() + settings.getOfflineTimeout()
        + 9000; clock.sleepUntil(clock.getTime() + 1000)) {
      spectator.loop(pm);
      assertEquals("user2", spectator.getCurrentPlayer(pm).getPlayer().getUserName());
    }

    spectator.loop(pm);

    assertEquals("user3", spectator.getCurrentPlayer(pm).getPlayer().getUserName());

    verify(twitch).announcePlayerSkipped(SkipReason.OFFLINE, player2.getPlayer());
  }

  @Test
  public void testPlayerIdle() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();

    QueuedPlayer player1 = getUser(pm, "user1");
    spectator.enqueue(pm, player1, false);
    spectator.enqueue(pm, getUser(pm, "user2"), false);
    spectator.loop(pm);

    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.WATCHING, ""));
    assertSpectatingUntil(pm, player1.getPlayer(), settings.getIdleTimeout());

    spectator.loop(pm);

    assertEquals("user2", spectator.getCurrentPlayer(pm).getPlayer().getUserName());

    verify(twitch).announcePlayerSkipped(SkipReason.IDLE, player1.getPlayer());
  }

  private void assertSpectatingUntil(PersistenceManager pm, OsuUser player, long until)
      throws InterruptedException {
    for (; clock.getTime() < until; clock.sleepUntil(clock.getTime() + 1000)) {
      spectator.loop(pm);
      assertEquals(player, spectator.getCurrentPlayer(pm).getPlayer());
    }
  }

  @Test
  public void testAutoQueue() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();

    for (int i = 1; i <= 5; i++) {
      OsuUser user = api.getUser("rank" + i, pm, 0);
      if (i == 3) {
        user.setAllowsSpectating(false);
      }
      ApiUser apiUser = api.getUserData(user.getUserId(), GameModes.OSU, pm, 0);
      apiUser.setRank(i);
      pm.makePersistent(new PlayerActivity(apiUser, Long.MAX_VALUE, 0));
    }

    for (int i = 0; i < 10; i++) {
      spectator.loop(pm);
      clock.sleepUntil(clock.getTime() + settings.getAutoSpecTime() / 2);
    }

    // rank 1 is actually spectated twice
    verify(osu, times(2)).startSpectate(api.getUser("rank1", pm, 0));
    verify(osu).startSpectate(api.getUser("rank2", pm, 0));
    // rank 3 is skipped
    verify(osu).startSpectate(api.getUser("rank4", pm, 0));
    verify(osu).startSpectate(api.getUser("rank5", pm, 0));
    verify(osu, times(9)).getClientStatus();
  }

  void testAutoQueueDistribution() throws Exception {
    List<ApiUser> recentlyActive = new ArrayList<>();
    Map<Integer, Long> lastQueueTime = new HashMap<>();
    SpectatorImpl spectator = new SpectatorImpl(twitch, clock, osu, settings, pmf, api, exec) {
      @Override
      List<ApiUser> getRecentlyActive(PersistenceManager pm) {
        return recentlyActive;
      }

      @Override
      Map<Integer, Long> getLastPlayTimes(PersistenceManager pm) {
        return lastQueueTime;
      }
    };

    PersistenceManager pm = pmf.getPersistenceManager();
    clock.sleepUntil(System.currentTimeMillis());

    for (int i = 1; i <= 1000; i++) {
      OsuUser user = api.getUser("rank" + i, pm, 0);
      ApiUser apiUser = api.getUserData(user.getUserId(), GameModes.OSU, pm, 0);
      apiUser.setRank(i);
      recentlyActive.add(apiUser);
    }

    Map<Integer, Integer> queueCount = new LinkedHashMap<>();

    for (int i = 0; i < 100000; i++) {
      OsuUser pick = spectator.pickAutoPlayer(pm, null).get().getPlayer();
      lastQueueTime.put(pick.getUserId(), clock.getTime());
      System.out.println(" " + i);
      queueCount.compute(pick.getUserId(), (k, v) -> v != null ? v + 1 : 1);

      clock.sleepUntil(clock.getTime() + settings.getAutoSpecTime());
    }

    queueCount.forEach((k, v) -> System.out.printf("%d %d%n", k, v));
  }

  public static void main(String[] args) throws Exception {
    SpectatorImplTest test = new SpectatorImplTest();
    test.createDatastore();
    test.initMocks();
    test.testAutoQueueDistribution();
  }

  @Test
  public void testGetQueueSize() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    assertEquals(0, spectator.getQueueSize(pm));
    spectator.enqueue(pm, getUser(pm, "player1"), false);
    assertEquals(1, spectator.getQueueSize(pm));
    spectator.loop(pm);
    assertEquals(0, spectator.getQueueSize(pm));
    spectator.enqueue(pm, getUser(pm, "player2"), false);
    spectator.enqueue(pm, getUser(pm, "player3"), false);
    spectator.enqueue(pm, getUser(pm, "player4"), false);
    spectator.enqueue(pm, getUser(pm, "player5"), false);
    spectator.loop(pm);
    assertEquals(4, spectator.getQueueSize(pm));
  }
  
  @Test
  public void testMinPlayCount() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();

    OsuUser user = api.getUser("player", pm, 0);
    api.getUserData(user.getUserId(), 0, pm, 0).setPlayCount(50);
    
    assertEquals(EnqueueResult.FAILURE, spectator.enqueue(pm, new QueuedPlayer(user, QueueSource.TWITCH, 0), false));
  }
  
  @Test
  public void testSkipNameTolerance() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    spectator.enqueue(pm, getUser(pm, "thatname"), false);
    spectator.loop(pm);
    spectator.enqueue(pm, getUser(pm, "someotherguy"), false);
    assertTrue(spectator.advanceConditional(pm, "thname"));
  }
  
  @Test
  public void testSkipNameToleranceFail() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    spectator.enqueue(pm, getUser(pm, "thatname"), false);
    spectator.loop(pm);
    spectator.enqueue(pm, getUser(pm, "someotherguy"), false);
    assertFalse(spectator.advanceConditional(pm, "thatotherguy"));
  }
  
  @Test
  public void testQueuePosition() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    spectator.enqueue(pm, getUser(pm, "player1"), false);
    spectator.enqueue(pm, getUser(pm, "player2"), false);
    spectator.enqueue(pm, getUser(pm, "player3"), false);
    assertEquals(2, spectator.getQueuePosition(pm, api.getUser("player2", pm, 0)));
  }
  
  @Test
  public void testQueuePositionNotInQueue() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    spectator.enqueue(pm, getUser(pm, "player1"), false);
    spectator.enqueue(pm, getUser(pm, "player3"), false);
    assertEquals(-1, spectator.getQueuePosition(pm, api.getUser("player2", pm, 0)));
  }
  
  @Test
  public void testLastActivity() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    assertEquals(EnqueueResult.SUCCESS, spectator.enqueue(pm, getUser(pm, "player1"), false));
    clock.sleepUntil(30L * 60 * 60 * 1000);
    assertEquals(EnqueueResult.FAILURE, spectator.enqueue(pm, getUser(pm, "player3"), false));
    // self-queue is fine
    assertEquals(EnqueueResult.SUCCESS, spectator.enqueue(pm, getUser(pm, "player3"), true));
  }
  
  @Test
  public void testSpectatingDenied() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    QueuedPlayer user = getUser(pm, "player3");
    user.getPlayer().setAllowsSpectating(false);
    assertEquals(EnqueueResult.DENIED, spectator.enqueue(pm, user, false));
    // self-queue is fine
    assertEquals(EnqueueResult.SUCCESS, spectator.enqueue(pm, user, true));
  }

  @Test
  public void testVoteQueue() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    QueuedPlayer user = getUser(pm, "player");
    assertTrue(spectator.voteQueue(pm, user, "voter"));
    assertFalse(spectator.voteQueue(pm, user, "voter"));
  }
  
  @Test
  public void testQueueVoting() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    QueuedPlayer user1 = getUser(pm, "player");
    
    spectator.enqueue(pm, user1, false);

    clock.sleepUntil(1000);
    QueuedPlayer user2 = getUser(pm, "player2");
    spectator.enqueue(pm, user2, false);
    clock.sleepUntil(2000);
    QueuedPlayer user3 = getUser(pm, "player3");
    spectator.enqueue(pm, user3, false);
    clock.sleepUntil(3000);
    QueuedPlayer user4 = getUser(pm, "player4");
    spectator.enqueue(pm, user4, false);
    
    PlayerQueue queue = PlayerQueue.loadQueue(pm, clock);
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user2, user3, user4), queue.queue);
    
    assertTrue(spectator.voteQueue(pm, user3, "twitch1"));
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user3, user2, user4), queue.queue);

    assertTrue(spectator.voteQueue(pm, user2, "twitch1"));
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user2, user3, user4), queue.queue);

    assertTrue(spectator.voteQueue(pm, user3, "twitch2"));
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user3, user2, user4), queue.queue);

    assertTrue(spectator.voteQueue(pm, user4, "twitch1"));
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user3, user2, user4), queue.queue);

    assertTrue(spectator.voteQueue(pm, user4, "twitch2"));
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user3, user2, user4), queue.queue);

    assertTrue(spectator.voteQueue(pm, user4, "twitch3"));
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user2, user3, user4), queue.queue);

    assertTrue(spectator.voteQueue(pm, user4, "twitch4"));
    queue.doSort(pm);
    assertEquals(Arrays.asList(user1, user4, user2, user3), queue.queue);
  }
  
  @Test
  public void testEndStatistic() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser user = api.getUser("someplayer", pm, 0);
    QueuedPlayer player = new QueuedPlayer(user, null, clock.getTime());
    spectator.enqueue(pm, player, false);
    spectator.enqueue(pm, new QueuedPlayer(api.getUser("someplayer2", pm, 0), 
        null, clock.getTime()), false);

    spectator.loop(pm);

    spectator.vote(pm, "flipflopper", VoteType.UP);
    spectator.vote(pm, "flipflopper", VoteType.UP);
    spectator.vote(pm, "flipflopper", VoteType.DOWN);
    
    spectator.advance(pm, PlayerQueue.loadQueue(pm, clock));

    verify(osu).notifyStatistics(player.getPlayer(), 2, 1);
  }
  
  @Test
  public void testEndStatisticFail() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser user = api.getUser("someplayer", pm, 0);
    QueuedPlayer player = new QueuedPlayer(user, null, clock.getTime());
    spectator.enqueue(pm, player, false);
    spectator.enqueue(pm, new QueuedPlayer(api.getUser("someplayer2", pm, 0), 
        null, clock.getTime()), false);

    spectator.loop(pm);

    spectator.vote(pm, "flipflopper", VoteType.UP);
    spectator.vote(pm, "flipflopper", VoteType.DOWN);
    spectator.vote(pm, "flipflopper", VoteType.DOWN);
    
    spectator.advance(pm, PlayerQueue.loadQueue(pm, clock));

    verify(osu, never()).notifyStatistics(player.getPlayer(), 2, 1);
  }
  
  @Test
  public void testPenalty() throws Exception {
    assertEquals(0d, SpectatorImpl.penalty(10, 0), 0d);
    assertEquals(0d, SpectatorImpl.penalty(10, 5), 0d);
    assertEquals(0d, SpectatorImpl.penalty(10, 10), 0d);
    assertEquals(.2, SpectatorImpl.penalty(10, 20), 0d);
    assertEquals(.3, SpectatorImpl.penalty(10, 40), 1E-15);
  }

  @Test
  public void testReportStatus() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    OsuUser tillerino = api.getUser("Tillerino", pm, 0);

    spectator.reportStatus(pm, new PlayerStatus(tillerino, PlayerStatusType.PLAYING, 0));
    
    assertNull(spectator.status.ingameStatus);
    
    spectator.promote(pm, tillerino);

    spectator.reportStatus(pm, new PlayerStatus(tillerino, PlayerStatusType.IDLE, 10));
    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.IDLE, 0), spectator.status.ingameStatus);

    spectator.reportStatus(pm, new PlayerStatus(tillerino, PlayerStatusType.PLAYING, 20));
    assertEquals(new PlayerStatus(tillerino, PlayerStatusType.PLAYING, 0), spectator.status.ingameStatus);

    spectator.reportStatus(pm, new PlayerStatus(tillerino, PlayerStatusType.OFFLINE, 0));
    assertEquals(PlayerStatusType.PLAYING, spectator.status.ingameStatus.getType());
    
    spectator.reportStatus(pm, new PlayerStatus(api.getUser("someotherguy", pm, 0), PlayerStatusType.AFK, 0));
    assertEquals(PlayerStatusType.PLAYING, spectator.status.ingameStatus.getType());
  }
  
  @Test
  public void testPollStatus() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.WATCHING, ""));

    QueuedPlayer queued = getUser(pm, "wontplay");
    spectator.enqueue(pm, queued, false);
    
    // advance queue
    spectator.loop(pm);
    // get client status
    spectator.loop(pm);
    // poll for ingame status
    spectator.loop(pm);
    
    verify(osu, times(1)).pollIngameStatus(queued.getPlayer());
  }
  
  @Test
  public void testPlayerAfk() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.WATCHING, ""));

    QueuedPlayer queued = getUser(pm, "wontplay");
    QueuedPlayer next = getUser(pm, "next");
    spectator.enqueue(pm, queued, false);
    spectator.enqueue(pm, next, false);
    
    doAnswer(
        x -> {
          spectator.reportStatus(pm, new PlayerStatus(queued.getPlayer(), PlayerStatusType.AFK,
              clock.getTime()));
          return null;
        }).when(osu).pollIngameStatus(queued.getPlayer());
    
    // advance queue
    spectator.loop(pm);
    
    assertSpectatingUntil(pm, queued.getPlayer(), settings.getIdleTimeout() / 2);
    
    spectator.loop(pm);
    
    assertEquals(next.getPlayer(), spectator.getCurrentPlayer(pm).getPlayer());
  }
  
  @Test
  public void testPlayerActuallyPlaying() throws Exception {
    PersistenceManager pm = pmf.getPersistenceManager();
    when(osu.getClientStatus()).thenReturn(new OsuStatus(Type.WATCHING, ""));

    QueuedPlayer queued = getUser(pm, "wontplay");
    QueuedPlayer next = getUser(pm, "next");
    spectator.enqueue(pm, queued, false);
    spectator.enqueue(pm, next, false);
    
    doAnswer(
        x -> {
          spectator.reportStatus(pm, new PlayerStatus(queued.getPlayer(), PlayerStatusType.PLAYING,
              clock.getTime()));
          return null;
        }).when(osu).pollIngameStatus(queued.getPlayer());
    
    // advance queue
    spectator.loop(pm);
    
    assertSpectatingUntil(pm, queued.getPlayer(), settings.getIdleTimeout() * 2);
    
    spectator.loop(pm);
    
    assertEquals(next.getPlayer(), spectator.getCurrentPlayer(pm).getPlayer());
  }
}
