<#-- SMSTunnel: add a phone number. The number gets a code; it is saved once the code comes back. -->
<#import "template.ftl" as layout>
<#import "smstunnel-fields.ftl" as st>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('phoneNumber') displayInfo=true; section>
    <#if section = "header">
        ${msg("smstunnelPhoneTitle")}
    <#elseif section = "form">
        <form id="kc-smstunnel-phone-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <p id="smstunnel-phone-intro">${msg("smstunnelPhoneIntro")}</p>

            <@st.textInput name="phoneNumber" label=msg("smstunnelPhoneLabel") value=(smstunnel.phoneNumber!"") type="tel" autocomplete="tel" inputmode="tel" autofocus=true help=msg("smstunnelPhoneHelp") />

            <@st.actions>
                <@st.primaryButton id="kc-smstunnel-send" label=msg("smstunnelPhoneSend") />
                <#if isAppInitiatedAction??>
                    <@st.secondaryButton id="kc-smstunnel-cancel" name="cancel-aia" label=msg("doCancel") />
                </#if>
            </@st.actions>
        </form>
    <#elseif section = "info">
        <div id="smstunnel-phone-info">${msg("smstunnelPhoneInfo")}</div>
        <#if !(isAppInitiatedAction??)>
            <div><a id="smstunnel-start-over" href="${url.loginRestartFlowUrl}">${msg("smstunnelStartOver")}</a></div>
        </#if>
    </#if>
</@layout.registrationLayout>
