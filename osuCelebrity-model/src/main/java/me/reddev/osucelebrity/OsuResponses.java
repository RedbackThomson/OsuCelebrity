package me.reddev.osucelebrity;

/**
 * Using static final fields instead of interface methods makes sense here because it enables us to
 * do some static code analysis for the {@link String#format(String, Object...)} method.
 */
public class OsuResponses extends Responses {
  /*
   * Notifications / allow deny spectating
   */
  
  public static final String MUTED = "You will no longer receive notifications. "
      + "Send !unmute to turn notifications back on.";
  public static final String UNMUTED = "Notifications have been turned on.";
  public static final String OPTOUT = "You will no longer be spectated by us. "
      + "Send !optin to allow us to spectate you again.";
  public static final String OPTIN = "You're now allowing us to spectate you.";
  
  static final String commands = "Disallow spectating: !optout. "
      + "Turn off notifications: !mute.";
  
  /*
   * Command response notifications
   */
  
  public static final String POSITION = "%s is position #%d in the queue.";
  public static final String NOT_IN_QUEUE = "%s is not in the queue.";
  public static final String NOW_PLAYING = "%s ( https://osu.ppy.sh/u/%d ) is currently playing %s";
  
  /*
   * Spectate-cycle notifications
   */
  
  /**
   * To the player who was queued.
   */
  public static final String QUEUED = "Someone has REQUESTED to spectate you "
      + "on [http://www.twitch.tv/osucelebrity OsuCelebrity]. "
      + "Your current position in line is %d. "
      + "I will notify you when you're next. Check position: !position. "
      + commands;
  
  /**
   * To the player who will be spectated next.
   */
  public static final String SPECTATING_NEXT = "You're NEXT in line to be spectated "
      + "on [http://www.twitch.tv/osucelebrity OsuCelebrity]. "
      + commands;
  
  /**
   * To the player who is being spectated now.
   */
  public static final String SPECTATING_NOW = "[http://www.twitch.tv/osucelebrity OsuCelebrity] "
      + "is now SPECTATING you. "
      + commands;
  
  /**
   * To the player who was being spectated.
   */
  public static final String DONE_SPECTATING = "[http://www.twitch.tv/osucelebrity OsuCelebrity] "
      + "has moved on to the next player. Thanks for playing! "
      + commands;
  
  /**
   * After the player has finished playing.
   */
  public static final String STATISTICS = "Your session statistics: %d danks, %d skips.";
  
  /*
   * ACCOUNT LINKING
   */
  public static final String LINKED = "Your osu! account has been linked to the twitch account %s.";
  public static final String ALREADY_LINKED = "Your osu! account is already linked"
      + " to a twitch account.";
  public static final String UNKNOWN_LINK = "Link string not found.";
  public static final String REPLAY = "Here's an [%s instant replay] of your performance.";
  public static final String UNAUTHORIZED = "You are not authorized for this command.";
  public static final String TIMED_OUT_CURRENTLY = "You are currently not allowed to use the bot.";
}
