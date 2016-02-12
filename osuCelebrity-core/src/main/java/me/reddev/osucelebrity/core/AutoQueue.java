package me.reddev.osucelebrity.core;

import static me.reddev.osucelebrity.osu.QOsuUser.osuUser;
import static me.reddev.osucelebrity.osuapi.QApiUser.apiUser;

import com.querydsl.jdo.JDOQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.reddev.osucelebrity.JdoQueryUtil;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import me.reddev.osucelebrity.osu.Osu;
import me.reddev.osucelebrity.osu.Osu.PollStatusConsumer;
import me.reddev.osucelebrity.osu.OsuUser;
import me.reddev.osucelebrity.osu.PlayerStatus;
import me.reddev.osucelebrity.osu.PlayerStatus.PlayerStatusType;
import me.reddev.osucelebrity.osuapi.ApiUser;
import org.tillerino.osuApiModel.GameModes;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

import javax.inject.Inject;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class AutoQueue {
  final Osu osu;
  final Spectator spectator;
  final Clock clock;
  final PersistenceManagerFactory pmf;
  final CoreSettings settings;

  static ToDoubleFunction<ApiUser> probability = user -> Math.pow(1 - 1E-2, user.getRank() + 50);

  Semaphore semaphore = new Semaphore(1);

  /**
   * Perform one attempt to auto-queue. Logs exceptions.
   */
  public void loop() {
    PersistenceManager pm = pmf.getPersistenceManager();

    try {
      loop(pm);
    } catch (Exception e) {
      log.error("exception", e);
    } finally {
      pm.close();
    }
  }

  void loop(PersistenceManager pm) throws InterruptedException {
    if (spectator.getQueueSize(pm) >= settings.getAutoQueueMaxSize()) {
      return;
    }
    
    if (!semaphore.tryAcquire(10, TimeUnit.SECONDS)) {
      log.warn("");
      semaphore = new Semaphore(1);
      semaphore.acquire();
    }
    int userId = drawUserId(pm);
    OsuUser user =
        JdoQueryUtil.getUnique(pm, osuUser, osuUser.userId.eq(userId)).orElseThrow(
            () -> new RuntimeException(userId + ""));
    pollStatus(user);
  }

  private int drawUserId(PersistenceManager pm) {
    double sum = 0d;
    TreeMap<Double, ApiUser> distribution = new TreeMap<>();
    try (JDOQuery<ApiUser> query = new JDOQuery<>(pm).select(apiUser).from(apiUser)) {
      List<ApiUser> users =
          query.where(apiUser.gameMode.eq(GameModes.OSU), apiUser.rank.loe(1000),
              apiUser.rank.goe(1)).fetch();
      for (ApiUser user : users) {
        double prob = probability.applyAsDouble(user);
        distribution.put(sum, user);
        sum += prob;
      }
    }
    double rnd = Math.random() * sum;
    Entry<Double, ApiUser> floorEntry = distribution.floorEntry(rnd);
    if (floorEntry == null) {
      throw new RuntimeException(rnd + " in " + sum);
    }
    int userId = floorEntry.getValue().getUserId();
    return userId;
  }

  private void pollStatus(OsuUser user) {
    Semaphore currentSemaphore = semaphore;
    PollStatusConsumer action = new PollStatusConsumer() {
      @Override
      public void accept(PersistenceManager pm, PlayerStatus status) throws IOException {
        currentSemaphore.release();
        if (status.getType() == PlayerStatusType.PLAYING) {
          QueuedPlayer queueRequest =
              new QueuedPlayer(status.getUser(), QueueSource.AUTO, clock.getTime());
          EnqueueResult result = spectator.enqueue(pm, queueRequest, false, null, true);
          log.debug("auto-queue: {}", result.formatResponse(status.getUser().getUserName()));
        }
      }
    };
    osu.pollIngameStatus(user, action);
  }
}
