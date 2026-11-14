import { formBindingData, FormExtensionAbility, formInfo } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { CardStore, layoutFor } from '../yoin/cards/CardStore';

const DOMAIN = 0x5901;
const TAG = 'YoinCards';

/**
 * Yoin's service cards (form_config.json: the Play card). The content is
 * written by the app (CardsPlugin → CardStore) whenever listening changes;
 * this extension only answers "what does a newly placed card show" and keeps
 * the list of placed cards so the app can refresh them.
 */
export default class EntryFormAbility extends FormExtensionAbility {
  onAddForm(want: Want): formBindingData.FormBindingData {
    const params = want.parameters ?? {};
    const formId = `${params[formInfo.FormParam.IDENTITY_KEY] ?? ''}`;
    const dimension = Number(params[formInfo.FormParam.DIMENSION_KEY] ?? formInfo.FormDimension.Dimension_2_2);
    hilog.info(DOMAIN, TAG, 'card placed: dimension %{public}d', dimension);
    if (formId.length > 0) {
      CardStore.addForm(this.context, formId, dimension);
    }
    const b = CardStore.binding(this.context, layoutFor(dimension));
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
