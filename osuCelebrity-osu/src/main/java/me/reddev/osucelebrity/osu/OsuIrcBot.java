package me.reddev.osucelebrity.osu;

import static me.reddev.osucelebrity.Commands.FORCESKIP;
import static me.reddev.osucelebrity.Commands.FORCESPEC;
import static me.reddev.osucelebrity.Commands.GAME_MODE;
import static me.reddev.osucelebrity.Commands.MUTE;
import static me.reddev.osucelebrity.Commands.OPTIN;
import static me.reddev.osucelebrity.Commands.OPTOUT;
import static me.reddev.osucelebrity.Commands.POSITION;
import static me.reddev.osucelebrity.Commands.QUEUE;
import static me.reddev.osucelebrity.Commands.QUEUE_SILENT;
import static me.reddev.osucelebrity.Commands.RESTART_CLIENT;
import static me.reddev.osucelebrity.Commands.SELFPOSITION;
import static me.reddev.osucelebrity.Commands.SELFQUEUE;
import static me.reddev.osucelebrity.Commands.UNMUTE;
import static me.reddev.osucelebrity.OsuResponses.TIMED_OUT_CURRENTLY;
import static me.reddev.osucelebrity.OsuResponses.UNAUTHORIZED;
import static me.reddev.osucelebrity.twitch.QTwitchUser.twitchUser;

import com.google.common.collect.ImmutableList;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.reddev.osucelebrity.AbstractIrcBot;
import me.reddev.osucelebrity.Commands;
import me.reddev.osucelebrity.JdoQueryUtil;
import me.reddev.osucelebrity.OsuResponses;
import me.reddev.osucelebrity.Privilege;
import me.reddev.osucelebrity.Responses;
import me.reddev.osucelebrity.UserException;
import me.reddev.osucelebrity.core.Clock;
import me.reddev.osucelebrity.core.EnqueueResult;
import me.reddev.osucelebrity.core.QueueVote;
import me.reddev.osucelebrity.core.QueuedPlayer;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import me.reddev.osucelebrity.core.Spectator;
import me.reddev.osucelebrity.osu.Osu.PollStatusConsumer;
import me.reddev.osucelebrity.osu.PlayerStatus.PlayerStatusType;
import me.reddev.osucelebrity.osuapi.OsuApi;
import me.reddev.osucelebrity.twitch.Twitch;
import me.reddev.osucelebrity.twitch.TwitchUser;
import org.apache.commons.lang3.tuple.Pair;
import org.pircbotx.Configuration;
import org.pircbotx.Configuration.Builder;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.QuitEvent;
import org.pircbotx.hooks.events.ServerResponseEvent;
import org.pircbotx.hooks.events.UnknownEvent;
import org.slf4j.Logger;
import org.tillerino.osuApiModel.GameModes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class OsuIrcBot extends AbstractIrcBot {
  @FunctionalInterface
  interface CommandHandler {
    boolean handle(PrivateMessageEvent event, String message, OsuUser user,
        PersistenceManager pm) throws UserException, IOException, InterruptedException;
  }

  @FunctionalInterface
  interface ConfirmedCommandHandler {
    void handle(PrivateMessageEvent event, String message, OsuUser user,
        PersistenceManager pm) throws UserException, IOException, InterruptedException;
  }
  
  /*
   * Injected
   */
  private final Osu osu;
  private final OsuApi osuApi;
  private final OsuIrcSettings ircSettings;
  private final PersistenceManagerFactory pmf;
  private final Spectator spectator;
  private final Clock clock;
  private final Twitch twitch;
  
  private final Pinger pinger;

  private Set<String> onlineUsers = new ConcurrentSkipListSet<>();

  final List<CommandHandler> handlers = new ArrayList<>();
  
  Map<Integer, BlockingQueue<Entry<Long, PollStatusConsumer>>> statusConsumers =
      new ConcurrentHashMap<>();

  {
    handlers.add(createHandler(this::handlePosition, POSITION));
    handlers.add(createHandler(this::handleSelfPosition, SELFPOSITION));
    handlers.add(createHandler(this::handleQueueSilent, QUEUE_SILENT));
    handlers.add(createHandler(this::handleQueue, QUEUE));
    handlers.add(createHandler(this::handleSelfQueue, SELFQUEUE));
    handlers.add(createHandler(this::handleSkip, FORCESKIP));
    handlers.add(this::handleMute);
    handlers.add(this::handleOpt);
    handlers.add(createHandler(this::handleSpec, FORCESPEC));
    handlers.add(createHandler(this::handleGameMode, GAME_MODE));
    handlers.add(createHandler(this::handleRestartClient, RESTART_CLIENT));
    handlers.add(createHandler(this::handleMod, Commands.MOD));
    handlers.add(createHandler(this::handleLink, Commands.FINISH_LINK));
  }
  
  @Override
  protected Configuration getConfiguration() {
    Builder configBuilder =
        new Configuration.Builder()
            .setName(ircSettings.getOsuIrcUsername())
            .setLogin(ircSettings.getOsuIrcUsername())
            .addListener(this)
            .addServer(ircSettings.getOsuIrcHost(), ircSettings.getOsuIrcPort())
            .setServerPassword(ircSettings.getOsuIrcPassword())
            .setAutoReconnect(true).setMessageDelay(500);
    Stream.of(ircSettings.getOsuIrcAutoJoin().split(",")).forEach(
        configBuilder::addAutoJoinChannel);
    return configBuilder.buildConfiguration();
  }

  /**
   * Notify the next player their turn will be up soon.
   * 
   * @param user The username of the next player
   */
  public void messagePlayer(OsuUser user, String message) {
    if (!ircSettings.isOsuIrcSilenced()) {
      if (synchronizeThroughPinger(() -> getBot().sendIRC().message(
          user.getUserName().replace(' ', '_'), message))) {
        log.debug("MESSAGED {}: {}", user.getUserName(), message);
      }
    }
  }

  // Listeners
  // http://site.pircbotx.googlecode.com/hg-history/2.0.1/apidocs/index.html

  @Override
  public void onPrivateMessage(PrivateMessageEvent event) {
    if (event.getUserHostmask().getNick().equals(ircSettings.getOsuCommandUser())) {
      handleBanchoBotResponse(event.getMessage());
      return;
    }

    log.debug("received message from {}: {}", event.getUserHostmask().getNick(), event.getMessage());
    
    if (!event.getMessage().startsWith(ircSettings.getOsuIrcCommand())) {
      return;
    }
    PersistenceManager pm = pmf.getPersistenceManager();
    try {

      OsuIrcUser ircUser = osuApi.getIrcUser(event.getUserHostmask().getNick(), pm, 0);
      OsuUser osuUser = ircUser != null ? ircUser.getUser() : null;
      if (osuUser == null) {
        throw new UserException("unrecognized user name");
      }
      
      if (osuUser.getTimeOutUntil() > clock.getTime()) {
        throw new UserException(TIMED_OUT_CURRENTLY);
      }
      
      String message = event.getMessage().substring(ircSettings.getOsuIrcCommand().length());

      for (CommandHandler commandHandler : handlers) {
        if (commandHandler.handle(event, message, osuUser, pm)) {
          break;
        }
      }
    } catch (Exception e) {
      UserException.handleException(log, e, message -> respond(event, message));
    } finally {
      pm.close();
    }
  }

  static Pattern statusPattern = Pattern.compile("Stats for \\(.*\\)\\[https://osu.ppy.sh/u/(\\d+)\\](?: is (Idle|Watching|Modding|Playing|Afk|Multiplaying))?:");
  
  @SuppressFBWarnings("TQ")
  Optional<PlayerStatus> parseStatus(PersistenceManager pm, String message) throws IOException {
    Matcher matcher = statusPattern.matcher(message);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    int userId = Integer.parseInt(matcher.group(1));
    String typeString = matcher.group(2);
    PlayerStatusType type =
        typeString == null ? PlayerStatusType.OFFLINE : PlayerStatusType.valueOf(typeString
            .toUpperCase());
    // measure time early in case the api blocks
    long time = clock.getTime();
    OsuUser user = osuApi.getUser(userId, pm, 0);
    if (user == null) {
      return Optional.empty();
    }
    return Optional.of(new PlayerStatus(user, type, time));
  }
  
  void handleBanchoBotResponse(String message) {
    PersistenceManager pm = pmf.getPersistenceManager();
    try {
      Optional<PlayerStatus> statusMaybe = parseStatus(pm, message);
      if (!statusMaybe.isPresent()) {
        return;
      }
      PlayerStatus status = statusMaybe.get();
      log.debug("status for {}: {}", status.getUser().getUserName(), status.getType());
      boolean handled = false;
      Queue<Entry<Long, PollStatusConsumer>> consumers =
          statusConsumers.get(status.getUser().getUserId());
      if (consumers != null) {
        for (Entry<Long, PollStatusConsumer> consumer; (consumer = consumers.poll()) != null; ) {
          if (consumer.getKey() < clock.getTime() - 60000L) {
            continue;
          }
          handled = true;
          try {
            consumer.getValue().accept(pm, status);
          } catch (Exception e) {
            UserException.handleException(log, e, null);
          }
        }
      }
      if (!handled) {
        spectator.reportStatus(pm, status);
      }
    } catch (Exception e) {
      log.error("error while handling bancho response", e);
    } finally {
      pm.close();
    }
  }

  CommandHandler createHandler(ConfirmedCommandHandler handler,
      String... triggers) {
    return (event, message, user, pm) -> {
      String remainingMessage = Commands.detect(message, triggers);
      if (remainingMessage == null) {
        return false;
      }
      log.debug("{} invokes {}", user.getUserName(), message);
      handler.handle(event, remainingMessage, user, pm);
      return true;
    };
  }
  
  void handleQueue(PrivateMessageEvent event, String queueTarget, OsuUser user,
      PersistenceManager pm) throws IOException, UserException {
    // Permits: !spec username : reason
    // Example: !spec Tillerino: for awesomeness Keepo
    queueTarget = queueTarget.split(":")[0].trim();

    OsuUser requestedUser = osuApi.getUser(queueTarget, pm, 60 * 60 * 1000);
    if (requestedUser == null) {
      throw new UserException(String.format(OsuResponses.INVALID_USER, queueTarget));
    }
    QueuedPlayer queueRequest = new QueuedPlayer(requestedUser, QueueSource.OSU, clock.getTime());
    spectator.performEnqueue(pm, queueRequest, QueueVote.OSU + user.getUserId(), log,
        msg -> respond(event, msg), msg -> respond(event, msg));
  }

  void handleQueueSilent(PrivateMessageEvent event, String queueTarget, OsuUser user,
      PersistenceManager pm) throws IOException, UserException {
    if (!user.getPrivilege().canSkip) {
      throw new UserException(UNAUTHORIZED);
    }
    
    OsuUser requestedUser = osuApi.getUser(queueTarget, pm, 60 * 60 * 1000);
    if (requestedUser == null) {
      throw new UserException(String.format(OsuResponses.INVALID_USER, queueTarget));
    }
    
    QueuedPlayer queueRequest = new QueuedPlayer(requestedUser, QueueSource.AUTO, clock.getTime());
    spectator.performEnqueue(pm, queueRequest, null, log, msg -> respond(event, msg),
        msg -> respond(event, msg));
  }

  void handleSpec(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    if (!user.getPrivilege().canSkip) {
      throw new UserException(UNAUTHORIZED);
    }
    
    OsuUser target = osuApi.getUser(message, pm, 0);
    if (target != null) {
      if (spectator.promote(pm, target)) {
        respond(event, "spectating " + target.getUserName());
      }
    }
  }

  void handleSelfQueue(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws IOException {
    QueuedPlayer queueRequest = new QueuedPlayer(user, QueueSource.OSU, clock.getTime());
    EnqueueResult result =
        spectator.enqueue(pm, queueRequest, true, QueueVote.OSU + user.getUserId(), true);
    if (result == EnqueueResult.SUCCESS) {
      respond(event, Responses.SELF_QUEUE_SUCCESSFUL);
    } else {
      respond(event, result.formatResponse(user.getUserName()));
    }
  }

  void handleSkip(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    if (!user.getPrivilege().canSkip) {
      throw new UserException(UNAUTHORIZED);
    }
    if (spectator.advanceConditional(pm, message)) {
      respond(event, "Skipped.");
    } else {
      respond(event, "Not skipped.");
    }
  }
  
  boolean handleMute(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    if (message.equalsIgnoreCase(MUTE)) {
      user.setAllowsNotifications(false);
      respond(event, String.format(OsuResponses.MUTED));
      return true;
    }
    if (message.equalsIgnoreCase(UNMUTE)) {
      user.setAllowsNotifications(true);
      respond(event, String.format(OsuResponses.UNMUTED));
      return true;
    }
    return false;
  }
  
  boolean handleOpt(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    if (message.equalsIgnoreCase(OPTOUT)) {
      user.setAllowsSpectating(false);
      log.debug("{} opted out. removing from queue", user.getUserName());
      QueuedPlayer oldPlayer = spectator.getCurrentPlayer(pm);
      QueuedPlayer newPlayer = spectator.removeFromQueue(pm, user);
      if (oldPlayer != null && newPlayer != null) {
        twitch.announceAdvance(null, oldPlayer.getPlayer(), newPlayer.getPlayer());
      }
      respond(event, String.format(OsuResponses.OPTOUT));
      return true;
    }
    if (message.equalsIgnoreCase(OPTIN)) {
      user.setAllowsSpectating(true);
      respond(event, String.format(OsuResponses.OPTIN));
      return true;
    }
    return false;
  }
  
  void handlePosition(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    OsuUser requestedUser = osuApi.getUser(message, pm, 60 * 60 * 1000);
    if (requestedUser == null) {
      return;
    }
    
    int position = spectator.getQueuePosition(pm, requestedUser);
    if (position != -1) {
      respond(event, String.format(OsuResponses.POSITION, 
          requestedUser.getUserName(), position));
    } else {
      respond(event, String.format(OsuResponses.NOT_IN_QUEUE, 
          requestedUser.getUserName()));
    }
  }
  
  void handleSelfPosition(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    int position = spectator.getQueuePosition(pm, user);
    if (position != -1) {
      respond(event, String.format(OsuResponses.POSITION, 
          user.getUserName(), position));
    } else {
      respond(event, String.format(OsuResponses.NOT_IN_QUEUE, 
          user.getUserName()));
    }
  }
  
  void handleGameMode(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    if (message.equalsIgnoreCase("osu")) {
      user.setGameMode(GameModes.OSU);
    } else if (message.equalsIgnoreCase("taiko")) {
      user.setGameMode(GameModes.TAIKO);
    } else if (message.equalsIgnoreCase("ctb")) {
      user.setGameMode(GameModes.CTB);
    } else if (message.equalsIgnoreCase("mania")) {
      user.setGameMode(GameModes.MANIA);
    } else {
      throw new UserException("Unknown game mode.");
    }
    
    respond(event, Responses.GAME_MODE_CHANGED);
  }
  
  void handleRestartClient(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException, InterruptedException {
    if (!user.getPrivilege().canRestartClient) {
      throw new UserException(UNAUTHORIZED);
    }
    
    osu.restartClient();
  }
  
  void handleMod(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException, InterruptedException {
    if (!user.getPrivilege().canMod) {
      throw new UserException(UNAUTHORIZED);
    }
    
    OsuUser target = osuApi.getUser(message, pm, 0);
    if (target == null) {
      throw new UserException("not found: " + message);
    }
    target.setPrivilege(Privilege.MOD);
    
    respond(event, "modded");
  }
  
  void handleLink(PrivateMessageEvent event, String message, OsuUser user,
      PersistenceManager pm) throws UserException, IOException {
    final TwitchUser userObject =
        JdoQueryUtil.getUnique(pm, twitchUser, twitchUser.linkString.eq(message)).orElseThrow(
            () -> new UserException(OsuResponses.UNKNOWN_LINK));
    
    if (JdoQueryUtil.getUnique(pm, twitchUser, twitchUser.osuUser.eq(user)).isPresent()) {
      throw new UserException(OsuResponses.ALREADY_LINKED);
    }
    
    // the twitch side makes sure that a twitch account can't be linked twice.
    pm.currentTransaction().begin();
    try {
      userObject.setOsuUser(user);
      userObject.setLinkString(null);
      pm.currentTransaction().commit();
    } finally {
      if (pm.currentTransaction().isActive()) {
        pm.currentTransaction().rollback();
      }
    }
    
    respond(event, String.format(OsuResponses.LINKED,
        userObject.getUser().getDisplayName()));
  }

  @Override
  public void onJoin(JoinEvent event) {
    // Ask for subscription and admin information
    if (event.getUserHostmask().getNick().equalsIgnoreCase(ircSettings.getOsuIrcUsername())) {
      log.info(String.format("Joined %s", event.getChannel().getName()));
    }
    onlineUsers.add(event.getUserHostmask().getNick());
  }

  @Override
  public void onQuit(QuitEvent event) throws Exception {
    onlineUsers.remove(event.getUserHostmask().getNick());
  }

  @Override
  public void onServerResponse(ServerResponseEvent event) throws Exception {
    if (event.getCode() == 353) {
      ImmutableList<String> parsedResponse = event.getParsedResponse();

      String[] usernames = parsedResponse.get(parsedResponse.size() - 1).split(" ");

      for (int i = 0; i < usernames.length; i++) {
        String nick = usernames[i];

        if (nick.startsWith("@") || nick.startsWith("+")) {
          nick = nick.substring(1);
        }

        onlineUsers.add(nick);
      }
    } else {
      super.onServerResponse(event);
    }
  }

  Set<String> getOnlineUsers() {
    return onlineUsers;
  }

  void pollIngameStatus(OsuUser player) {
    if (synchronizeThroughPinger(() -> getBot().sendIRC().message(ircSettings.getOsuCommandUser(),
        "!stat " + player.getUserName()))) {
      log.debug("POLLED STATUS for: {}", player.getUserName());
    }
  }

  void pollIngameStatus(OsuUser player, PollStatusConsumer action) {
    statusConsumers.computeIfAbsent(player.getUserId(), x -> new LinkedBlockingQueue<>()).add(
        Pair.of(clock.getTime(), action));
    pollIngameStatus(player);
  }

  @Override
  public void onUnknown(UnknownEvent event) throws Exception {
    pinger.handleUnknownEvent(event);
  }
  
  synchronized boolean synchronizeThroughPinger(Runnable runnable) {
    try {
      pinger.ping(getBot());
      runnable.run();
      return true;
    } catch (IOException e) {
      log.warn("IOException while waiting for pong: {}", e.getMessage());
      return false;
    } catch (InterruptedException e) {
      log.warn("Interrupted while waiting for pong: {}", e.getMessage());
      return false;
    }
  }
  
  void respond(PrivateMessageEvent event, String response) {
    if (synchronizeThroughPinger(() -> event.respond(response))) {
      log.debug("RESPONDED to {}: {}", event.getUserHostmask().getNick(), response);
    }
  }

  @Override
  protected Logger getLog() {
    return log;
  }
}
