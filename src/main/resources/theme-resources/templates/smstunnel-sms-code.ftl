<#-- SMSTunnel SMS code: the page that asks for the code (at sign-in, and when a new number is checked). -->
<#import "template.ftl" as layout>
<#import "smstunnel-fields.ftl" as st>
<#assign verify = (smstunnel.mode!"login") == "verify">
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('code') displayInfo=true; section>
    <#if section = "header">
        <#if verify>${msg("smstunnelPhoneVerifyTitle")}<#else>${msg("smstunnelCodeTitle")}</#if>
    <#elseif section = "form">
        <form id="kc-smstunnel-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#if (smstunnel.sent!"false") == "true">
                <p id="smstunnel-code-sent">
                    <#if verify>
                        ${msg("smstunnelPhoneCodeSent", smstunnel.phoneNumber!"")}
                    <#elseif (smstunnel.again!"false") == "true">
                        ${msg("smstunnelCodeSentAgain", smstunnel.phoneEnding!"")}
                    <#else>
                        ${msg("smstunnelCodeSent", smstunnel.phoneEnding!"")}
                    </#if>
                </p>
            <#else>
                <p id="smstunnel-code-not-sent">${msg("smstunnelCodeNotSent", smstunnel.phoneEnding!"")}</p>
            </#if>

            <@st.textInput name="code" label=msg("smstunnelCodeLabel") autocomplete="one-time-code" inputmode="numeric" maxlength=(smstunnel.codeLength!"10") autofocus=true />

            <@st.actions>
                <@st.primaryButton id="kc-smstunnel-submit" label=msg(verify?then("smstunnelPhoneConfirm", "smstunnelSignIn")) />
                <@st.secondaryButton id="kc-smstunnel-resend" name="resend" label=msg("smstunnelResend") />
                <#if verify>
                    <@st.secondaryButton id="kc-smstunnel-change" name="changeNumber" label=msg("smstunnelPhoneChange") />
                </#if>
                <#if isAppInitiatedAction??>
                    <@st.secondaryButton id="kc-smstunnel-cancel" name="cancel-aia" label=msg("doCancel") />
                </#if>
            </@st.actions>
        </form>
    <#elseif section = "info">
        <div id="smstunnel-code-help">${msg("smstunnelCodeHelp", smstunnel.ttlMinutes!"5")}</div>
        <#if !(isAppInitiatedAction??)>
            <div><a id="smstunnel-start-over" href="${url.loginRestartFlowUrl}">${msg("smstunnelStartOver")}</a></div>
        </#if>
    </#if>
</@layout.registrationLayout>
