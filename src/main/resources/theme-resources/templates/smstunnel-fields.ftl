<#-- SMSTunnel form pieces: PatternFly 5 markup in the keycloak.v2 theme, the older markup in other themes. -->
<#macro textInput name label value="" type="text" autocomplete="off" inputmode="text" maxlength="" autofocus=false help="">
  <#assign error = messagesPerField.get(name)>
  <#if properties.kcFormGroupLabelClass??>
    <div class="${properties.kcFormGroupClass!}">
      <div class="${properties.kcFormGroupLabelClass!}">
        <label for="${name}" class="${properties.kcFormLabelClass!}">
          <span class="${properties.kcFormLabelTextClass!}">${label}</span>
        </label>
      </div>
      <span class="${properties.kcInputClass!}<#if error?has_content> ${properties.kcError!}</#if>">
        <input id="${name}" name="${name}" value="${value}" type="${type}" autocomplete="${autocomplete}" inputmode="${inputmode}"<#if maxlength?has_content> maxlength="${maxlength}"</#if><#if autofocus> autofocus</#if> aria-invalid="<#if error?has_content>true<#else>false</#if>"<#if error?has_content> aria-describedby="input-error-${name}"</#if>/>
      </span>
      <div id="input-error-container-${name}">
        <#if error?has_content>
          <div class="${properties.kcFormHelperTextClass!}" aria-live="polite">
            <div class="${properties.kcInputHelperTextClass!}">
              <div class="${properties.kcInputHelperTextItemClass!} ${properties.kcError!}" id="input-error-${name}">
                <span class="${properties.kcInputErrorMessageClass!}">${error}</span>
              </div>
            </div>
          </div>
        <#elseif help?has_content>
          <div class="${properties.kcFormHelperTextClass!}">
            <div class="${properties.kcInputHelperTextClass!}">
              <div class="${properties.kcInputHelperTextItemClass!}">
                <span class="${properties.kcInputHelperTextItemTextClass!}">${help}</span>
              </div>
            </div>
          </div>
        </#if>
      </div>
    </div>
  <#else>
    <div class="${properties.kcFormGroupClass!}">
      <div class="${properties.kcLabelWrapperClass!}">
        <label for="${name}" class="${properties.kcLabelClass!}">${label}</label>
      </div>
      <div class="${properties.kcInputWrapperClass!}">
        <input id="${name}" name="${name}" value="${value}" type="${type}" class="${properties.kcInputClass!}" autocomplete="${autocomplete}" inputmode="${inputmode}"<#if maxlength?has_content> maxlength="${maxlength}"</#if><#if autofocus> autofocus</#if> aria-invalid="<#if error?has_content>true<#else>false</#if>"/>
        <#if error?has_content>
          <span id="input-error-${name}" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">${error}</span>
        <#elseif help?has_content>
          <span class="${properties.kcInputHelperTextClass!}">${help}</span>
        </#if>
      </div>
    </div>
  </#if>
</#macro>

<#macro actions>
  <div class="${properties.kcFormGroupClass!}">
    <#if properties.kcFormActionGroupClass??>
      <div class="${properties.kcFormActionGroupClass!} pf-v5-u-flex-wrap"><#nested></div>
    <#else>
      <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}"><#nested></div>
    </#if>
  </div>
</#macro>

<#macro primaryButton id label>
  <button id="${id}" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" name="login" value="true">${label}</button>
</#macro>

<#-- formnovalidate: these buttons work with an empty field. -->
<#macro secondaryButton id name label>
  <button id="${id}" class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonSecondaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" name="${name}" value="true" formnovalidate>${label}</button>
</#macro>
