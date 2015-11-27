package me.reddev.osucelebrity.osu;

import me.reddev.osucelebrity.PassAndReturnNonnull;

import java.util.List;
import javax.annotation.CheckForNull;
import javax.jdo.PersistenceManager;

@PassAndReturnNonnull
public interface Osu {
  /**
   * Starts spectating a player. TODO specify behaviour (async with callback? success/failure?)
   * 
   * @param user The user to be spectated.
   */
  public void startSpectate(OsuUser user);

  /**
   * Sends a message via ingame chat.
   * 
   * @param osuUser recipient
   * @param message message
   */
  public void message(OsuUser osuUser, String message);

  /**
   * Notifies a player about their upcoming spectate period.
   * 
   * @param user Recipient
   */
  public void notifyStarting(OsuUser user);

  /**
   * Notifies a player that they're going to be spectated next.
   * 
   * @param osuUser the player to be notified.
   */
  public void notifyNext(OsuUser osuUser);

  /**
   * Notifies a player that they've been added to the queue.
   * 
   * @param osuUser the player to be notified.
   * @param queuePosition TODO
   */
  public void notifyQueued(OsuUser osuUser, int queuePosition);

  /**
   * Notifies a player that their session is over.
   * 
   * @param osuUser the player to be notified.
   */
  public void notifyDone(OsuUser osuUser);
  
  /**
   * Notifies a player of their session statistics.
   * @param player the player to be notified.
   * @param danks the number of danks in their session.
   * @param skips the number of skips in their session.
   */
  void notifyStatistics(OsuUser osuUser, long danks, long skips);

  /**
   * Retrieves the client status.
   * 
   * @return null if the status is unknown.
   */
  @CheckForNull
  public OsuStatus getClientStatus();

  /**
   * Returns a list of users who are definitely online. Note that this list is not complete.
   */
  public List<String> getOnlineUsers();

  /**
   * Checks if this player is definitely online. Note that the player might still be online if this
   * method returns false.
   */
  public boolean isOnline(OsuUser player);

  /**
   * Returns the timestamp of the last completed play.
   * 
   * @return 0 if unknown.
   */
  public long lastActivity(PersistenceManager pm, OsuUser player);
  
  /**
   * Poll for the ingame status of a player.
   * 
   * @param player the target player.
   */
  public void pollIngameStatus(OsuUser player);
}
