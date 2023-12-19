/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.integrations.base.ssh;

import com.google.common.base.Preconditions;
import io.airbyte.cdk.integrations.base.AirbyteTraceMessageUtility;
import io.airbyte.commons.exceptions.ConfigErrorException;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// todo (cgardens) - this needs unit tests. it is currently tested transitively via source postgres
// integration tests.
/**
 * Encapsulates the connection configuration for an ssh tunnel port forward through a proxy/bastion
 * host plus the remote host and remote port to forward to a specified local port.
 */
public abstract class SshTunnel<CONFIG_TYPE> implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SshTunnel.class);
  public static final String SSH_TIMEOUT_DISPLAY_MESSAGE =
      "Timed out while opening a SSH Tunnel. Please double check the given SSH configurations and try again.";

  public enum TunnelMethod {
    NO_TUNNEL,
    SSH_PASSWORD_AUTH,
    SSH_KEY_AUTH
  }

  public static final int TIMEOUT_MILLIS = 15000; // 15 seconds

  protected final CONFIG_TYPE config;
  protected final List<String> hostKey;
  protected final List<String> portKey;

  protected final TunnelMethod tunnelMethod;
  private final String tunnelHost;
  private final int tunnelPort;
  private final String tunnelUser;
  private final String sshKey;
  protected final String endPointKey;
  protected final String remoteServiceProtocol;
  protected final String remoteServicePath;
  private final String tunnelUserPassword;
  private final String remoteServiceHost;
  private final int remoteServicePort;
  protected int tunnelLocalPort;

  private SshClient sshclient;
  private ClientSession tunnelSession;

  /**
   *
   * @param config - the full config that was passed to the source.
   * @param hostKey - a list of keys that point to the database host name. should be pointing to where
   *        in the config remoteDatabaseHost is found.
   * @param portKey - a list of keys that point to the database port. should be pointing to where in
   *        the config remoteDatabasePort is found.
   * @param endPointKey - key that points to the endpoint URL (this is commonly used for REST-based
   *        services such as Elastic and MongoDB)
   * @param remoteServiceUrl - URL of the remote endpoint (this is commonly used for REST-based *
   *        services such as Elastic and MongoDB)
   * @param tunnelMethod - the type of ssh method that should be used (includes not using SSH at all).
   * @param tunnelHost - host name of the machine to which we will establish an ssh connection (e.g.
   *        hostname of the bastion).
   * @param tunnelPort - port of the machine to which we will establish an ssh connection. (e.g. port
   *        of the bastion).
   * @param tunnelUser - user that is allowed to access the tunnelHost.
   * @param sshKey - the ssh key that will be used to make the ssh connection. can be null if we are
   *        using tunnelUserPassword instead.
   * @param tunnelUserPassword - the password for the tunnelUser. can be null if we are using sshKey
   *        instead.
   * @param remoteServiceHost - the actual host name of the remote service (as it is known to the
   *        tunnel host).
   * @param remoteServicePort - the actual port of the remote service (as it is known to the tunnel
   *        host).
   */
  public SshTunnel(final CONFIG_TYPE config,
                   final List<String> hostKey,
                   final List<String> portKey,
                   final String endPointKey,
                   final String remoteServiceUrl,
                   final TunnelMethod tunnelMethod,
                   final String tunnelHost,
                   final int tunnelPort,
                   final String tunnelUser,
                   final String sshKey,
                   final String tunnelUserPassword,
                   final String remoteServiceHost,
                   final int remoteServicePort) {
    this.config = config;
    this.hostKey = hostKey;
    this.portKey = portKey;
    this.endPointKey = endPointKey;
    Preconditions.checkNotNull(tunnelMethod);
    this.tunnelMethod = tunnelMethod;

    if (tunnelMethod.equals(TunnelMethod.NO_TUNNEL)) {
      this.tunnelHost = null;
      this.tunnelPort = 0;
      this.tunnelUser = null;
      this.sshKey = null;
      this.tunnelUserPassword = null;
      this.remoteServiceHost = null;
      this.remoteServicePort = 0;
      this.remoteServiceProtocol = null;
      this.remoteServicePath = null;
    } else {
      Preconditions.checkNotNull(tunnelHost);
      Preconditions.checkArgument(tunnelPort > 0);
      Preconditions.checkNotNull(tunnelUser);
      if (tunnelMethod.equals(TunnelMethod.SSH_KEY_AUTH)) {
        Preconditions.checkNotNull(sshKey);
      }
      if (tunnelMethod.equals(TunnelMethod.SSH_PASSWORD_AUTH)) {
        Preconditions.checkNotNull(tunnelUserPassword);
      }
      // must provide either host/port or endpoint
      Preconditions.checkArgument((hostKey != null && portKey != null) || endPointKey != null);
      Preconditions.checkArgument((remoteServiceHost != null && remoteServicePort > 0) || remoteServiceUrl != null);
      if (remoteServiceUrl != null) {
        URL urlObject = null;
        try {
          urlObject = new URL(remoteServiceUrl);
        } catch (final MalformedURLException e) {
          AirbyteTraceMessageUtility.emitConfigErrorTrace(e,
              String.format("Provided value for remote service URL is not valid: %s", remoteServiceUrl));
        }
        Preconditions.checkNotNull(urlObject, "Failed to parse URL of remote service");
        this.remoteServiceHost = urlObject.getHost();
        this.remoteServicePort = urlObject.getPort();
        this.remoteServiceProtocol = urlObject.getProtocol();
        this.remoteServicePath = urlObject.getPath();
      } else {
        this.remoteServiceProtocol = null;
        this.remoteServicePath = null;
        this.remoteServiceHost = remoteServiceHost;
        this.remoteServicePort = remoteServicePort;
      }

      this.tunnelHost = tunnelHost;
      this.tunnelPort = tunnelPort;
      this.tunnelUser = tunnelUser;
      this.sshKey = sshKey;
      this.tunnelUserPassword = tunnelUserPassword;
      this.sshclient = createClient();
      this.tunnelSession = openTunnel(sshclient);
    }
  }

  public CONFIG_TYPE getOriginalConfig() {
    return config;
  }

  public abstract CONFIG_TYPE getConfigInTunnel() throws Exception;

  /**
   * Closes a tunnel if one was open, and otherwise doesn't do anything (safe to run).
   */
  @Override
  public void close() {
    try {
      if (tunnelSession != null) {
        tunnelSession.close();
        tunnelSession = null;
      }
      if (sshclient != null) {
        sshclient.stop();
        sshclient = null;
      }
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * From the OPENSSH private key string, use mina-sshd to deserialize the key pair, reconstruct the
   * keys from the key info, and return the key pair for use in authentication.
   *
   * @return The {@link KeyPair} to add - may not be {@code null}
   * @see <a href=
   *      "https://javadoc.io/static/org.apache.sshd/sshd-common/2.8.0/org/apache/sshd/common/config/keys/loader/KeyPairResourceLoader.html#loadKeyPairs-org.apache.sshd.common.session.SessionContext-org.apache.sshd.common.util.io.resource.IoResource-org.apache.sshd.common.config.keys.FilePasswordProvider-">loadKeyPairs()</a>
   */
  KeyPair getPrivateKeyPair() throws IOException, GeneralSecurityException {
    final String validatedKey = validateKey();
    final var keyPairs = SecurityUtils
        .getKeyPairResourceParser()
        .loadKeyPairs(null, null, null, new StringReader(validatedKey));

    if (keyPairs != null && keyPairs.iterator().hasNext()) {
      return keyPairs.iterator().next();
    }
    throw new ConfigErrorException("Unable to load private key pairs, verify key pairs are properly inputted");
  }

  private String validateKey() {
    return sshKey.replace("\\n", "\n");
  }

  /**
   * Generates a new ssh client and returns it, with forwarding set to accept all types; use this
   * before opening a tunnel.
   */
  private SshClient createClient() {
    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    final SshClient client = SshClient.setUpDefaultClient();
    client.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
    client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
    CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ZERO);
    return client;
  }

  /**
   * Starts an ssh session; wrap this in a try-finally and use closeTunnel() to close it.
   */
  ClientSession openTunnel(final SshClient client) {
    try {
      client.start();
      final ClientSession session = client.connect(
          tunnelUser.trim(),
          tunnelHost.trim(),
          tunnelPort)
          .verify(TIMEOUT_MILLIS)
          .getSession();
      if (tunnelMethod.equals(TunnelMethod.SSH_KEY_AUTH)) {
        session.addPublicKeyIdentity(getPrivateKeyPair());
      }
      if (tunnelMethod.equals(TunnelMethod.SSH_PASSWORD_AUTH)) {
        session.addPasswordIdentity(tunnelUserPassword);
      }

      session.auth().verify(TIMEOUT_MILLIS);
      final SshdSocketAddress address = session.startLocalPortForwarding(
          // entering 0 lets the OS pick a free port for us.
          new SshdSocketAddress(InetSocketAddress.createUnresolved(SshdSocketAddress.LOCALHOST_ADDRESS.getHostName(), 0)),
          new SshdSocketAddress(remoteServiceHost, remoteServicePort));

      // discover the port that the OS picked and remember it so that we can use it when we try to connect
      tunnelLocalPort = address.getPort();

      LOGGER.info(String.format("Established tunneling session to %s:%d. Port forwarding started on %s ",
          remoteServiceHost, remoteServicePort, address.toInetSocketAddress()));
      return session;
    } catch (final IOException | GeneralSecurityException e) {
      if (e instanceof SshException && e.getMessage()
          .toLowerCase(Locale.ROOT)
          .contains("failed to get operation result within specified timeout")) {
        throw new ConfigErrorException(SSH_TIMEOUT_DISPLAY_MESSAGE, e);
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public String toString() {
    return "SshTunnel{" +
        "hostKey=" + hostKey +
        ", portKey=" + portKey +
        ", tunnelMethod=" + tunnelMethod +
        ", tunnelHost='" + tunnelHost + '\'' +
        ", tunnelPort=" + tunnelPort +
        ", tunnelUser='" + tunnelUser + '\'' +
        ", remoteServiceHost='" + remoteServiceHost + '\'' +
        ", remoteServicePort=" + remoteServicePort +
        ", tunnelLocalPort=" + tunnelLocalPort +
        '}';
  }

}
