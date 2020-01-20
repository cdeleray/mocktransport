package com.github.cdeleray.mail.mocktransport;

import javax.mail.MessagingException;
import javax.mail.event.TransportListener;

/**
 * A {@code MessageFaultMock} object represents a mock object that can be
 * configured to mimic some faults when messages have to be delivered.
 *
 * @author Christophe Deleray
 */
public interface MessageFaultMock {
  /**
   * Records all addresses matching the given patterns so that they are marked
   * as invalid.
   *
   * @param regexes the regular expressions that tell which address a message
   * will not be delivered to
   */
  void markAsInvalid(String... regexes);

  /**
   * Marks all addresses matching the given patterns so that they are marked as
   * valid but no message can be sent to.
   *
   * @param regexes the regular expressions that tell which address a message
   * will not be delivered to, even if this recipient is valid
   */
  void markAsValidUnsent(String... regexes);

  /**
   * Enables or disables the <em>failure mode</em>, that causes a
   * {@link MessagingException} and notifies any registered
   * {@link TransportListener}.
   *
   * @param mode {@code true} to activate the <em>failure mode</em>; {@code false}
   * otherwise
   */
  void setFailureMode(boolean mode);

  /**
   * Sets the percentage of failure that can potentially happen when sending
   * messages.
   * <p>
   * This method activates the {@linkplain #setFailureMode(boolean) <em>failure
   * mode</em>}.
   *
   * @param failureRate a failure rate expressed as a value comprised within the
   * range [0,100]
   * @throws IllegalArgumentException if {@code failureRate} is not between 0
   * and 100
   */
  void setFailureRate(int failureRate);
}
