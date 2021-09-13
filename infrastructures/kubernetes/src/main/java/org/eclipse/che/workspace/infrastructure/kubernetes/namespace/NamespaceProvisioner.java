/*
 * Copyright (c) 2012-2021 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.kubernetes.namespace;

import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.DEV_WORKSPACE_MOUNT_AS_ANNOTATION;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.DEV_WORKSPACE_MOUNT_LABEL;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.DEV_WORKSPACE_MOUNT_PATH_ANNOTATION;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.USER_PREFERENCES_SECRET_NAME;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.USER_PROFILE_SECRET_NAME;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.user.User;
import org.eclipse.che.api.core.notification.EventSubscriber;
import org.eclipse.che.api.user.server.PreferenceManager;
import org.eclipse.che.api.user.server.UserManager;
import org.eclipse.che.api.user.server.event.PostUserPersistedEvent;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.NamespaceResolutionContext;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.subject.Subject;
import org.eclipse.che.workspace.infrastructure.kubernetes.KubernetesClientFactory;
import org.eclipse.che.workspace.infrastructure.kubernetes.api.shared.KubernetesNamespaceMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NamespaceProvisioner implements EventSubscriber<PostUserPersistedEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(NamespaceProvisioner.class);
  private final KubernetesNamespaceFactory namespaceFactory;
  private final PreferenceManager preferenceManager;
  private final KubernetesClientFactory clientFactory;
  private final UserManager userManager;

  @Inject
  public NamespaceProvisioner(
      KubernetesNamespaceFactory namespaceFactory,
      KubernetesClientFactory clientFactory,
      UserManager userManager,
      PreferenceManager preferenceManager) {
    this.namespaceFactory = namespaceFactory;
    this.clientFactory = clientFactory;
    this.userManager = userManager;
    this.preferenceManager = preferenceManager;
  }

  public KubernetesNamespaceMeta provision() throws InfrastructureException {

    Subject subject = EnvironmentContext.getCurrent().getSubject();
    KubernetesNamespaceMeta kubernetesNamespaceMeta =
        namespaceFactory.provision(new NamespaceResolutionContext(subject));

    try {
      createOrUpdateSecrets(userManager.getById(subject.getUserId()));
    } catch (NotFoundException | ServerException e) {
      LOG.error("Could not find current user. Skipping creation of user information secrets.", e);
    } catch (InfrastructureException e) {
      LOG.error("There was a failure while creating user information secrets.", e);
    }

    return kubernetesNamespaceMeta;
  };

  @Override
  public void onEvent(PostUserPersistedEvent event) {
    try {
      createOrUpdateSecrets(event.getUser());
    } catch (InfrastructureException e) {
      LOG.error("There was a failure while creating user information secrets.", e);
    }
  }

  private void createOrUpdateSecrets(User user) throws InfrastructureException {

    final Map<String, String> userProfileData = new HashMap<>();
    userProfileData.put("id", user.getId());
    userProfileData.put("name", user.getName());
    userProfileData.put("email", user.getEmail());

    String namespace =
        namespaceFactory.evaluateNamespaceName(
            new NamespaceResolutionContext(null, user.getId(), user.getName()));

    clientFactory
        .create()
        .secrets()
        .inNamespace(namespace)
        .withName(USER_PROFILE_SECRET_NAME)
        .createOrReplace(
            new SecretBuilder()
                .addToData(userProfileData)
                .withNewMetadata()
                .addToLabels(DEV_WORKSPACE_MOUNT_LABEL, "true")
                .addToAnnotations(DEV_WORKSPACE_MOUNT_AS_ANNOTATION, "file")
                .addToAnnotations(DEV_WORKSPACE_MOUNT_PATH_ANNOTATION, "/config/user/profile")
                .endMetadata()
                .build());

    Map<String, String> preferences;
    try {
      preferences = preferenceManager.find(user.getId());
    } catch (ServerException e) {
      LOG.error(
          "Could not find user preferences. Skipping creation of user preferences secrets.", e);
      return;
    }

    clientFactory
        .create()
        .secrets()
        .inNamespace(namespace)
        .withName(USER_PREFERENCES_SECRET_NAME)
        .createOrReplace(
            new SecretBuilder()
                .addToData(preferences)
                .withNewMetadata()
                .addToLabels(DEV_WORKSPACE_MOUNT_LABEL, "true")
                .addToAnnotations(DEV_WORKSPACE_MOUNT_AS_ANNOTATION, "file")
                .addToAnnotations(DEV_WORKSPACE_MOUNT_PATH_ANNOTATION, "/config/user/preferences")
                .endMetadata()
                .build());
  }
}
