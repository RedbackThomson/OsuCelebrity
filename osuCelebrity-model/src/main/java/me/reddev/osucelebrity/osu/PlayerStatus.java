package me.reddev.osucelebrity.osu;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Player status as can be seen in-game.
 */
@Data
@AllArgsConstructor
@EqualsAndHashCode(exclude = "receivedAt")
public class PlayerStatus {
  public enum PlayerStatusType {
    OFFLINE, IDLE, PLAYING, WATCHING, MODDING, AFK
  }

  OsuUser user;

  PlayerStatusType type;

  long receivedAt;
}
