function createSaveFeedbackController() {
    const buttonLabels = new WeakMap();
    const buttonTimers = new WeakMap();

    function remember(button) {
        if (button && !buttonLabels.has(button)) {
            buttonLabels.set(button, button.textContent.trim());
        }
    }

    function reset(button) {
        if (!button) {
            return;
        }

        const timer = buttonTimers.get(button);
        if (timer) {
            clearTimeout(timer);
            buttonTimers.delete(button);
        }

        button.disabled = false;
        button.dataset.saveState = "";
        button.textContent = buttonLabels.get(button) || button.textContent;
    }

    function setTemporary(button, label, stateName, timeoutMs = 2200) {
        if (!button) {
            return;
        }

        const timer = buttonTimers.get(button);
        if (timer) {
            clearTimeout(timer);
        }

        button.disabled = false;
        button.dataset.saveState = stateName;
        button.textContent = label;
        buttonTimers.set(button, setTimeout(() => reset(button), timeoutMs));
    }

    async function run(button, action, labels = {}) {
        if (!button) {
            return action();
        }

        remember(button);
        reset(button);
        button.disabled = true;
        button.dataset.saveState = "loading";
        button.textContent = labels.loading || "Speichert...";

        try {
            const result = await action();
            setTemporary(button, labels.success || "Gespeichert", "success");
            return result;
        } catch (error) {
            setTemporary(button, labels.error || "Speichern fehlgeschlagen", "error", 2600);
            throw error;
        }
    }

    return {
        remember,
        reset,
        run
    };
}

window.createSaveFeedbackController = createSaveFeedbackController;
