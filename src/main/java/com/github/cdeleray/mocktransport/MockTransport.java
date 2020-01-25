/*
 * MIT License
 *
 * Copyright (c) 2020 Christophe Deleray
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.cdeleray.mocktransport;

import static java.util.Arrays.asList;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static javax.mail.event.TransportEvent.MESSAGE_DELIVERED;
import static javax.mail.event.TransportEvent.MESSAGE_NOT_DELIVERED;
import static javax.mail.event.TransportEvent.MESSAGE_PARTIALLY_DELIVERED;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Service;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.URLName;
import javax.mail.event.ConnectionEvent;
import javax.mail.event.MailEvent;
import javax.mail.event.TransportEvent;
import javax.mail.event.TransportListener;

/**
 * A {@code MockTransport} object represents a mock implementation
 * of the {@link Transport} class that provides also some configuration
 * feature in order to mimic failures and (partially or not) message
 * delivering.
 * <p>
 * A {@code MockTransport} object provides two strategies to deliver {@link
 * MailEvent}s (e.g. {@link TransportEvent} or {@link ConnectionEvent}) :
 * <ul>
 *   <li>- the {@linkplain EventDispatchStrategy#ASYNCHRONOUS asynchronous}
 *   strategy, that is defined by the {@link Service} class. It means that
 *   threads are used when delivering a {@link MailEvent}. <br><br>
 *   </li>
 *   <li>- the {@linkplain EventDispatchStrategy#SYNCHRONOUS synchronous}
 *   strategy, that is defined by this {@code MockTransport} class. It means that
 *   each {@link MailEvent} is delivered to interested listener within the
 *   <em>current</em> thread.
 *   </li>
 * </ul>
 * <p>
 * This class is not intended to be instantiated directly by the application
 * code as explain in the Chapter 5 of the
 * <a href="http://www.oracle.com/technetwork/java/javamail-1-149769.pdf">
 * JavaMail</a> specification. The normal design is to use the
 * {@code javamail.providers} or {@code javamail.default.providers} file
 * and let the JavaMail framework instantiates it on our behalf (see also
 * the {@link Session} class and the code of its {@link Session#getTransport()
 * getTransport} method for further details).
 *
 * <p>
 * A typical use of this this class is to specify in the classpath a file named
 * {@code /META-INF/javamail.providers} file that contains a line as in the
 * following:
 * <pre>
 * {@code protocol=smtp; type=transport; class=com.github.cdeleray.mocktransport.MockTransport}
 * </pre>
 *
 * @author Christophe Deleray
 */
public class MockTransport extends Transport {
  /** The list of addresses to be considered as invalid. */
  private final List<Pattern> invalidAddresses = new ArrayList<>();

  /** The list of addresses to be considered as valid but for which no
   * message can be sent to. */
  private final List<Pattern> validUnsentAddresses = new ArrayList<>();

  /**
   * A barrier point expressed in percentage from which a mail can be sent
   * safely without error.
   * <p>
   * In order to determine whether a mail has to be sent with an error or
   * not, a value is randomly taken from the [0, 100] range. If this value
   * is greater or equals than this barrier point, the mail is then sent safely.
   * Otherwise, an error is raised.
   * <p>
   * A negative value means that the failure mode is deactivated so that
   * every mails will be sent successfully. A value greater than 100 means
   * that every mails will not be sent and then an error is raised.
   * <p>
   * This is 0 by default.
   */
  private volatile int successBarrier;

  /** Used to supply random values comprised between 0 and 100. */
  private final Random random = new Random();

  /** {@code  100%} as a constant. */
  private static final int CENT_PER_CENT = 100;

  /**
   * The strategy to apply when delivering {@link MailEvent}s to interested
   * listeners.
   * <p>
   * This is the {@linkplain EventDispatchStrategy#SYNCHRONOUS synchronous}
   * strategy by default.
   */
  private EventDispatchStrategy eventDispatchStrategy = EventDispatchStrategy.SYNCHRONOUS;

  /**
   * Enumerates the strategies for delivering {@link MailEvent}, among synchronous
   * or asynchronous.
   *
   * @author Christophe Deleray
   */
  public enum EventDispatchStrategy {
    /**
     * The <em>synchronous</em> strategy.
     * <p>
     * With this strategy, every {@link MailEvent}s (e.g. {@linkplain
     * TransportEvent} or {@link ConnectionEvent}) will be dispatched
     * within the <em>current</em> thread.
     */
    SYNCHRONOUS,

    /**
     * The <em>asynchronous</em> strategy.
     * <p>
     * This is the behavior defined by default by the {@link Service} class,
     * because it used {@link Thread}s (with or without {@link Executor})
     * -based queue when processing {@link MailEvent}s.
     */
    ASYNCHRONOUS
  }

  /**
   * Creates a new {@code MockTransport} object.
   *
   * @param session Session object for this Transport.
   * @param urlname URLName object to be used for this Transport
   */
  public MockTransport(Session session, URLName urlname) {
    super(session, urlname);

    String key = getClass().getName()+".failureRate";
    setFailureRate(Integer.getInteger(key, 0)); // do not worry, this is a final method
  }

  @Override
  public void sendMessage(Message msg, Address[] addresses) throws MessagingException {
    synchronized(this) {
      msg.saveChanges(); //as the javadoc says

      if (successBarrier > 0) {
        int value = random.nextInt(CENT_PER_CENT + 1); //take a random value within the [0,100] range

        if (value < successBarrier) {
          notifyTransportListeners(MESSAGE_NOT_DELIVERED, new Address[0], addresses, new Address[0], msg);
          throw new MessagingException("sendMessage has failed");
        }
      }

      List<Address> all = new ArrayList<>(Arrays.asList(addresses));
      Address[] validUnsent = removeMatched(all, validUnsentAddresses);
      Address[] invalid = removeMatched(all, invalidAddresses);
      Address[] validSent = all.toArray(new Address[0]);

      int type = (validUnsent.length > 0) ? MESSAGE_PARTIALLY_DELIVERED : MESSAGE_DELIVERED;
      notifyTransportListeners(type, validSent, validUnsent, invalid, msg);
    }
  }

  @Override
  public void connect(String host, int port, String user, String password) {
    synchronized (this) {
      setConnected(true);
      notifyConnectionListeners(ConnectionEvent.OPENED);
    }
  }

  /**
   * Records all addresses matching the given patterns so that they are marked
   * as invalid.
   *
   * @param regexes the regular expressions that tell which address a message
   * will not be delivered to
   */
  public void markAsInvalid(String... regexes) {
    addPatterns(invalidAddresses, regexes);
  }

  /**
   * Marks all addresses matching the given patterns so that they are marked as
   * valid but no message can be sent to.
   *
   * @param regexes the regular expressions that tell which address a message
   * will not be delivered to, even if this recipient is valid
   */
  public void markAsValidUnsent(String... regexes) {
    addPatterns(validUnsentAddresses, regexes);
  }

  /**
   * Enables or disables the <em>failure mode</em>, that causes a
   * {@link MessagingException} and notifies any registered
   * {@link TransportListener}.
   *
   * @param mode {@code true} to activate the <em>failure mode</em>; {@code false}
   * otherwise
   */
  public void setFailureMode(boolean mode) {
    this.successBarrier = mode ? CENT_PER_CENT + 1 : 0;
  }

  /**
   * Sets the percentage of failure for the {@link #send(Message, Address[])}
   * method.
   * <p>
   * This method activates the {@linkplain #setFailureMode(boolean) <em>failure
   * mode</em>}.
   *
   * @param failureRate a failure rate expressed as a value comprised within
   * the range [0,100]
   * @throws IllegalArgumentException if {@code failureRate} is not between 0 and 100
   */
  public final void setFailureRate(int failureRate) {
    if (failureRate < 0 || failureRate > 100) {
      throw new IllegalArgumentException("The failure rate must be in the range [0, 100].");
    }

    this.successBarrier = failureRate;
  }

  /**
   * Defines the strategy to apply when delivering {@link MailEvent}s.
   * <p>
   * By default, all {@link MailEvent}s are dispatched to the interested
   * listeners in a synchronous way, within the current thread. This is
   * not the behavior defined by default by the {@link Service} class,
   * because it used {@link Thread}s (with or without {@link Executor})
   * -based queue when processing {@link MailEvent}s.
   * <p>
   * The strategy defined by this method is then used by the {@link
   * #queueEvent(MailEvent, Vector)} method.
   *
   * @param strategy the strategy to apply
   */
  public void setEventDispatchStrategy(EventDispatchStrategy strategy) {
    this.eventDispatchStrategy = strategy;
  }

  @Override
  protected void queueEvent(MailEvent event, Vector vector) {
    if (eventDispatchStrategy == EventDispatchStrategy.ASYNCHRONOUS) {
      super.queueEvent(event, vector);
    }
    else {
      synchronized(this) {
        vector.forEach(event::dispatch);
      }
    }
  }

  /** Appends the given patterns to the given list. */
  private void addPatterns(List<Pattern> patterns, String[] regexes) {
    synchronized (this) {
      Stream.of(regexes)
          .map(regex -> Pattern.compile(regex, CASE_INSENSITIVE))
          .forEach(patterns::add);
    }
  }

  /** Returns the addresses contained into {@code addresses} that match at least
   * a pattern among those specified by {@code patterns}, and removes them
   * from {@code addresses}. */
  private Address[] removeMatched(List<Address> addresses, List<Pattern> patterns) {
    Address[] retained = addresses.stream()
        .filter(address -> patterns.stream().anyMatch(pattern -> pattern.matcher(address.toString()).matches()))
        .toArray(Address[]::new);

    addresses.removeAll(asList(retained));
    return retained;
  }
}