/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.events.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentUpdatingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.localization.LocalizationManager;
import org.xwiki.mail.MailListener;
import org.xwiki.mail.MailSender;
import org.xwiki.mail.MimeMessageFactory;
import org.xwiki.mail.SessionFactory;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.user.UserProperties;
import org.xwiki.user.UserPropertiesResolver;
import org.xwiki.user.UserReferenceResolver;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Event listener that will notify reviewers in case a status change is detected.
 * @since 1.0
 * @version $Id$
 */
@Component
@Singleton
@Named(NotifyReviewersEventListener.LISTENER_NAME)
public class NotifyReviewersEventListener extends AbstractEventListener
{
    /**
     * The listener name.
     */
    public static final String LISTENER_NAME = "NotifyPresentationReviewersEventListener";

    /**
     * The space where the presentation code is located.
     */
    public static final List<String> PRESENTATION_CODE_SPACE = Arrays.asList("Presentation", "Code");

    private static final LocalDocumentReference PRESENTATION_CLASS_REFERENCE =
        new LocalDocumentReference(PRESENTATION_CODE_SPACE, "PresentationClass");

    private static final LocalDocumentReference REVIEWER_MAIL_TEMPLATE_REFERENCE =
        new LocalDocumentReference(PRESENTATION_CODE_SPACE, "ReviewerMailTemplate");

    private static final String STATUS_FIELD_NAME = "status";

    @Inject
    @Named("template")
    private MimeMessageFactory<MimeMessage> templateMimeMessageFactory;

    @Inject
    private SessionFactory sessionFactory;

    @Inject
    private MailSender mailSender;

    @Inject
    private UserReferenceResolver<String> userReferenceResolver;

    @Inject
    private UserPropertiesResolver userPropertiesResolver;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private LocalizationManager localizationManager;

    @Inject
    private Logger logger;

    /**
     * Create a new {@link NotifyReviewersEventListener}.
     */
    public NotifyReviewersEventListener()
    {
        super(LISTENER_NAME, new DocumentUpdatingEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiDocument sourceDoc = (XWikiDocument) source;
        XWikiContext xwikiContext = (XWikiContext) data;
        String notifyReviewers = xwikiContext.getRequest().get("notifyReviewers");

        if (sourceDoc.getXObject(PRESENTATION_CLASS_REFERENCE) != null
            && notifyReviewers != null) {

            if (Boolean.parseBoolean(notifyReviewers)) {
                // Check if the change is about a status change to "review"
                String currentStatus = sourceDoc.getStringValue(PRESENTATION_CLASS_REFERENCE, STATUS_FIELD_NAME);
                XWikiDocument originalDoc = sourceDoc.getOriginalDocument();
                if (!originalDoc.getStringValue(PRESENTATION_CLASS_REFERENCE, STATUS_FIELD_NAME).equals(currentStatus))
                {
                    sendNotifications(sourceDoc, xwikiContext, (DocumentUpdatingEvent) event);
                }
            }
        }
    }

    private void sendNotifications(XWikiDocument document, XWikiContext context, DocumentUpdatingEvent event)
    {
        DocumentReference presentationClassReference =
            new DocumentReference(PRESENTATION_CLASS_REFERENCE, context.getWikiReference());
        Session session = sessionFactory.create(Collections.emptyMap());

        // Extract every reviewer that need to be notified
        String[] reviewersReferences =
            document.getStringValue(presentationClassReference, "reviewers").split("\\|");
        try {
            for (String reviewerReference : reviewersReferences) {
                sendSingleNotification(document, context, session, reviewerReference);
            }
        } catch (MessagingException | ComponentLookupException | XWikiException e) {
            logger.error("Failed to send notification email to presentation reviewers for document [{}].",
                document.getDocumentReference(), e);
            event.cancel(localizationManager.getTranslationPlain("Failed to send notification email to reviweers",
                context.getLocale()));
        }
    }

    private void sendSingleNotification(XWikiDocument document, XWikiContext context, Session session,
        String userReference) throws MessagingException, ComponentLookupException, XWikiException
    {
        UserProperties userProperties = userPropertiesResolver.resolve(userReferenceResolver.resolve(userReference));
        DocumentReference mailTemplateReference =
            new DocumentReference(REVIEWER_MAIL_TEMPLATE_REFERENCE, context.getWikiReference());

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("language", context.getLocale().toLanguageTag());
        parameters.put("to", userProperties.getEmail());

        Map<String, Object> velocityVariables = new HashMap<>();
        velocityVariables.put("presentationReference", document.getDocumentReference());
        velocityVariables.put("url", document.getExternalURL("view", context));
        velocityVariables.put("title", document.getTitle());
        velocityVariables.put("firstName", userProperties.getFirstName());
        velocityVariables.put("lastName", userProperties.getLastName());
        velocityVariables.put(STATUS_FIELD_NAME,
            document.getStringValue(PRESENTATION_CLASS_REFERENCE, STATUS_FIELD_NAME));
        parameters.put("velocityVariables", velocityVariables);

        MimeMessage message =
            templateMimeMessageFactory.createMessage(mailTemplateReference, parameters);
        mailSender.sendAsynchronously(Collections.singleton(message), session,
            componentManager.getInstance(MailListener.class, "memory"));
    }
}
