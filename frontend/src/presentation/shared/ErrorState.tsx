import { RefreshCw } from "lucide-react";
import { Button, FeedbackState } from "../../design-system";
import { requestErrorPresentation } from "./requestErrorPresentation";

export type ErrorStateProps = { error: unknown; retry?: () => void };

export function ErrorState({ error, retry }: ErrorStateProps) {
  const presentation = requestErrorPresentation(error);
  return (
    <FeedbackState
      kind="error"
      title={presentation.title}
      description={presentation.description}
      reference={presentation.reference}
      action={retry ? <Button variant="secondary" onClick={retry}><RefreshCw size={16} /> 다시 시도</Button> : undefined}
    />
  );
}
