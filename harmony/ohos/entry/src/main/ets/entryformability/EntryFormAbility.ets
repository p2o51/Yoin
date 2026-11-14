import { formBindingData, FormExtensionAbility, formInfo } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { CardStore, PLAY_FORM } from '../yoin/cards/CardStore';

const DOMAIN = 0x5901;
const TAG = 'YoinCards';

/**
 * Yoin's service cards (form_config.json: the Play card and the Memory card). The content is
 * written by the app (CardsPlugin → CardStore) whenever listening changes;
 * this extension only answers "what does a newly placed card show" and keeps
 * the list of placed cards so the app can refresh them.
 */
export default class EntryFormAbility extends FormExtensionAbility {
  onAddForm(want: Want): formBindingData.FormBindingData {
    const params = want.parameters ?? {};
    const formId = `${params[formInfo.FormParam.IDENTITY_KEY] ?? ''}`;
    const dimension = Number(params[formInfo.FormParam.DIMENSION_KEY] ?? formInfo.FormDimension.Dimension_2_2);
    const name = `${params[formInfo.FormParam.NAME_KEY] ?? PLAY_FORM}`;
    hilog.info(DOMAIN, TAG, 'card placed: %{public}s, dimension %{public}d', name, dimension);
    if (formId.length > 0) {
      CardStore.addForm(this.context, formId, dimension, name);
    }
    const b = CardStore.bindingFor(this.context, name, dimension);
    // The system reads the images after this returns.
    setTimeout(() => CardStore.closeFds(b.fds), 5000);
    return b.binding;
  }

  onUpdateForm(formId: string): void {
    CardStore.refreshOne(this.context, formId);
  }

  onRemoveForm(formId: string): void {
    CardStore.removeForm(this.context, formId);
  }

  onCastToNormalForm(formId: string): void {
  }

  onChangeFormVisibility(newStatus: Record<string, number>): void {
  }

  onFormEvent(formId: string, message: string): void {
  }

  onAcquireFormState(want: Want): formInfo.FormState {
    return formInfo.FormState.READY;
  }
}
