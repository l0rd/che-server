/*
 * Copyright (c) 2012-2023 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.api.factory.server.scm.kubernetes;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.che.api.factory.server.scm.PersonalAccessTokenFetcher.OAUTH_2_PREFIX;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.secret.KubernetesSecretAnnotationNames.ANNOTATION_AUTOMOUNT;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.secret.KubernetesSecretAnnotationNames.ANNOTATION_DEV_WORKSPACE_MOUNT_PATH;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.secret.KubernetesSecretAnnotationNames.ANNOTATION_GIT_CREDENTIALS;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.secret.KubernetesSecretAnnotationNames.ANNOTATION_MOUNT_AS;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.secret.KubernetesSecretAnnotationNames.ANNOTATION_MOUNT_PATH;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.secret.KubernetesSecretAnnotationNames.DEV_WORKSPACE_PREFIX;

import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.eclipse.che.api.factory.server.scm.GitCredentialManager;
import org.eclipse.che.api.factory.server.scm.PersonalAccessToken;
import org.eclipse.che.api.factory.server.scm.exception.ScmConfigurationPersistenceException;
import org.eclipse.che.api.factory.server.scm.exception.UnsatisfiedScmPreconditionException;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.commons.lang.NameGenerator;
import org.eclipse.che.commons.lang.StringUtils;
import org.eclipse.che.workspace.infrastructure.kubernetes.CheServerKubernetesClientFactory;
import org.eclipse.che.workspace.infrastructure.kubernetes.api.shared.KubernetesNamespaceMeta;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesNamespaceFactory;

/**
 * Creates or updates Git credentials secret in user's namespace to allow Git operations on private
 * repositories.
 */
@Singleton
public class KubernetesGitCredentialManager implements GitCredentialManager {
  public static final String NAME_PATTERN = "git-credentials-secret-";
  public static final String ANNOTATION_SCM_URL = "che.eclipse.org/scm-url";
  public static final String ANNOTATION_SCM_USERNAME = "che.eclipse.org/scm-username";
  public static final String ANNOTATION_CHE_USERID = "che.eclipse.org/che-userid";
  public static final String CREDENTIALS_MOUNT_PATH = "/.git-credentials";
  public static final String LABEL_DEV_WORKSPACE_CREDENTIAL =
      DEV_WORKSPACE_PREFIX + "/git-credential";

  public static final String LABEL_DEV_WORKSPACE_WATCH_SECRET =
      "controller.devfile.io/watch-secret";

  // Labels that that are use to search for already existing secret.
  private static final Map<String, String> SEARCH_LABELS =
      ImmutableMap.of(
          "app.kubernetes.io/part-of", "che.eclipse.org",
          "app.kubernetes.io/component", "workspace-secret");
  // Labels that will be added to newly created secret.
  private static final Map<String, String> NEW_SECRET_LABELS =
      ImmutableMap.<String, String>builder()
          .putAll(SEARCH_LABELS)
          .put(LABEL_DEV_WORKSPACE_CREDENTIAL, "true")
          .put(LABEL_DEV_WORKSPACE_WATCH_SECRET, "true")
          .build();

  static final Map<String, String> DEFAULT_SECRET_ANNOTATIONS =
      ImmutableMap.of(
          ANNOTATION_AUTOMOUNT, "true",
          ANNOTATION_MOUNT_PATH, CREDENTIALS_MOUNT_PATH,
          ANNOTATION_MOUNT_AS, "file",
          ANNOTATION_GIT_CREDENTIALS, "true",
          ANNOTATION_DEV_WORKSPACE_MOUNT_PATH, CREDENTIALS_MOUNT_PATH);

  private final KubernetesNamespaceFactory namespaceFactory;
  private final CheServerKubernetesClientFactory cheServerKubernetesClientFactory;

  @Inject
  public KubernetesGitCredentialManager(
      KubernetesNamespaceFactory namespaceFactory,
      CheServerKubernetesClientFactory cheServerKubernetesClientFactory) {
    this.namespaceFactory = namespaceFactory;
    this.cheServerKubernetesClientFactory = cheServerKubernetesClientFactory;
  }

  @Override
  public void createOrReplace(PersonalAccessToken personalAccessToken)
      throws UnsatisfiedScmPreconditionException, ScmConfigurationPersistenceException {
    try {
      final String namespace = getFirstNamespace();
      final KubernetesClient client = cheServerKubernetesClientFactory.create();
      // to avoid duplicating secrets we try to reuse existing one by matching
      // hostname/username if possible, and update it. Otherwise, create new one.
      Optional<Secret> existing =
          client
              .secrets()
              .inNamespace(namespace)
              .withLabels(SEARCH_LABELS)
              .list()
              .getItems()
              .stream()
              .filter(s -> s.getMetadata().getAnnotations() != null)
              .filter(
                  s ->
                      Boolean.parseBoolean(
                              s.getMetadata().getAnnotations().get(ANNOTATION_GIT_CREDENTIALS))
                          && personalAccessToken
                              .getScmProviderUrl()
                              .equals(
                                  StringUtils.trimEnd(
                                      s.getMetadata().getAnnotations().get(ANNOTATION_SCM_URL),
                                      '/'))
                          && personalAccessToken
                              .getCheUserId()
                              .equals(s.getMetadata().getAnnotations().get(ANNOTATION_CHE_USERID)))
              .findFirst();

      Secret secret =
          existing.orElseGet(
              () -> {
                Map<String, String> annotations = new HashMap<>(DEFAULT_SECRET_ANNOTATIONS);
                annotations.put(ANNOTATION_SCM_URL, personalAccessToken.getScmProviderUrl());
                annotations.put(ANNOTATION_CHE_USERID, personalAccessToken.getCheUserId());
                ObjectMeta meta =
                    new ObjectMetaBuilder()
                        .withName(NameGenerator.generate(NAME_PATTERN, 5))
                        .withAnnotations(annotations)
                        .withLabels(NEW_SECRET_LABELS)
                        .build();
                return new SecretBuilder().withMetadata(meta).build();
              });
      URL scmUrl = new URL(personalAccessToken.getScmProviderUrl());
      secret.setData(
          Map.of(
              "credentials",
              Base64.getEncoder()
                  .encodeToString(
                      format(
                              "%s://%s:%s@%s%s",
                              scmUrl.getProtocol(),
                              getUsernameSegment(personalAccessToken),
                              URLEncoder.encode(personalAccessToken.getToken(), UTF_8),
                              scmUrl.getHost(),
                              scmUrl.getPort() != 80 && scmUrl.getPort() != -1
                                  ? ":" + scmUrl.getPort()
                                  : "")
                          .getBytes())));
      client.secrets().inNamespace(namespace).createOrReplace(secret);
    } catch (InfrastructureException | MalformedURLException e) {
      throw new ScmConfigurationPersistenceException(e.getMessage(), e);
    }
  }

  /**
   * Returns username URL segment for git credentials. For OAuth2 tokens it is "oauth2", for others
   * - {@param personalAccessToken#getScmUserName()} or just "username" string if the token has a
   * non-null {@param personalAccessToken#getScmOrganization()}. This is needed to support providers
   * that do not have username in their user object. Such providers have an additional organization
   * field.
   */
  private String getUsernameSegment(PersonalAccessToken personalAccessToken) {
    return personalAccessToken.getScmTokenName().startsWith(OAUTH_2_PREFIX)
        ? "oauth2"
        : isNullOrEmpty(personalAccessToken.getScmOrganization())
            ? personalAccessToken.getScmUserName()
            : "username";
  }

  /**
   * It is not guaranteed that this code will always return same namespace, but since credentials
   * are now added into manually pre-created user namespace, we can expect always the same result
   * will be returned.
   */
  private String getFirstNamespace()
      throws UnsatisfiedScmPreconditionException, ScmConfigurationPersistenceException {
    try {
      Optional<String> namespace =
          namespaceFactory.list().stream().map(KubernetesNamespaceMeta::getName).findFirst();
      if (namespace.isEmpty()) {
        throw new UnsatisfiedScmPreconditionException(
            "No user namespace found. Cannot read SCM credentials.");
      }
      return namespace.get();
    } catch (InfrastructureException e) {
      throw new ScmConfigurationPersistenceException(e.getMessage(), e);
    }
  }
}
