/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.tez;

import org.apache.hadoop.hive.registry.impl.TezAmInstance;

import org.apache.hadoop.security.token.Token;
import org.apache.tez.common.security.JobTokenIdentifier;

import org.apache.hadoop.conf.Configuration;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.auth.login.LoginException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.tez.dag.api.TezException;

import com.google.common.annotations.VisibleForTesting;

/**
 * TezSession that is aware of the session pool, and also keeps track of expiration and use.
 * It has 3 states - not in use, in use, and expired. When in the pool, it is not in use;
 * use and expiration may compete to take the session out of the pool and change it to the
 * corresponding states. When someone tries to get a session, they check for expiration time;
 * if it's time, the expiration is triggered; in that case, or if it was already triggered, the
 * caller gets a different session. When the session is in use when it expires, the expiration
 * thread ignores it and lets the return to the pool take care of the expiration.
 */
@VisibleForTesting
class TezSessionPoolSession extends TezSessionState {
  private static final int STATE_NONE = 0, STATE_IN_USE = 1, STATE_EXPIRED = 2;

  interface Manager {
    void registerOpenSession(TezSessionPoolSession session);
    void unregisterOpenSession(TezSessionPoolSession session);
    void returnAfterUse(TezSessionPoolSession session) throws Exception;
    TezSessionState reopen(TezSessionPoolSession session, Configuration conf,
        String[] inputOutputJars) throws Exception;
    void destroy(TezSessionPoolSession session) throws Exception;
  }

  private final AtomicInteger sessionState = new AtomicInteger(STATE_NONE);
  private Long expirationNs;
  private final Manager parent;
  private final SessionExpirationTracker expirationTracker;


  public TezSessionPoolSession(String sessionId, Manager parent,
      SessionExpirationTracker tracker, HiveConf conf) {
    super(sessionId, conf);
    this.parent = parent;
    this.expirationTracker = tracker;
  }

  void setExpirationNs(long expirationNs) {
    this.expirationNs = expirationNs;
  }

  Long getExpirationNs() {
    return expirationNs;
  }

  @Override
  void close(boolean keepTmpDir) throws Exception {
    try {
      super.close(keepTmpDir);
    } finally {
      parent.unregisterOpenSession(this);
      if (expirationTracker != null) {
        expirationTracker.removeFromExpirationQueue(this);
      }
    }
  }

  @Override
  protected void openInternal(Collection<String> additionalFiles,
      boolean isAsync, LogHelper console, Path scratchDir)
          throws IOException, LoginException, URISyntaxException, TezException {
    super.openInternal(additionalFiles, isAsync, console, scratchDir);
    parent.registerOpenSession(this);
    if (expirationTracker != null) {
      expirationTracker.addToExpirationQueue(this);
    }
  }

  @Override
  public String toString() {
    if (expirationNs == null) return super.toString();
    long expiresInMs = (expirationNs - System.nanoTime()) / 1000000L;
    return super.toString() + ", expires in " + expiresInMs + "ms";
  }

  /**
   * Tries to use this session. When the session is in use, it will not expire.
   * @return true if the session can be used; false if it has already expired.
   */
  public boolean tryUse() throws Exception {
    while (true) {
      int oldValue = sessionState.get();
      if (oldValue == STATE_IN_USE) throw new AssertionError(this + " is already in use");
      if (oldValue == STATE_EXPIRED) return false;
      int finalState = shouldExpire() ? STATE_EXPIRED : STATE_IN_USE;
      if (sessionState.compareAndSet(STATE_NONE, finalState)) {
        if (finalState == STATE_IN_USE) return true;
        // Restart asynchronously, don't block the caller.
        expirationTracker.closeAndRestartExpiredSession(this, true);
        return false;
      }
    }
  }

  boolean stopUsing() throws Exception {
    int finalState = shouldExpire() ? STATE_EXPIRED : STATE_NONE;
    if (!sessionState.compareAndSet(STATE_IN_USE, finalState)) {
      throw new AssertionError("Unexpected state change; currently " + sessionState.get());
    }
    if (finalState == STATE_NONE) return true;
    expirationTracker.closeAndRestartExpiredSession(this, true);
    return false;
  }

  /**
   * Tries to expire and restart the session.
   * @param isAsync Whether the restart should happen asynchronously.
   * @return True if the session was, or will be restarted.
   */
  public boolean tryExpire(boolean isAsync) throws Exception {
    if (expirationNs == null) return true;
    if (!shouldExpire()) return false;
    // Try to expire the session if it's not in use; if in use, bail.
    while (true) {
      if (sessionState.get() != STATE_NONE) return true; // returnAfterUse will take care of this
      if (sessionState.compareAndSet(STATE_NONE, STATE_EXPIRED)) {
        expirationTracker.closeAndRestartExpiredSession(this, isAsync);
        return true;
      }
    }
  }

  private boolean shouldExpire() {
    return expirationNs != null && (System.nanoTime() - expirationNs) >= 0;
  }

  @Override
  public void returnToSessionManager() throws Exception {
    parent.returnAfterUse(this);
  }

  @Override
  public TezSessionState reopen(
      Configuration conf, String[] inputOutputJars) throws Exception {
    return parent.reopen(this, conf, inputOutputJars);
  }

  @Override
  public void destroy() throws Exception {
    parent.destroy(this);
  }

  boolean isOwnedBy(Manager parent) {
    return this.parent == parent;
  }

  void updateFromRegistry(TezAmInstance si) {
    // Nothing to do.
  }
}