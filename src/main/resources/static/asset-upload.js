(function () {
    const ACCEPT = "image/png,image/jpeg,image/gif,image/webp";
    const MAX_FILE_SIZE_BYTES = 3 * 1024 * 1024;

    async function uploadFile(file) {
        if (!file) {
            throw new Error("Bitte zuerst eine Bilddatei auswählen.");
        }
        if (file.size > MAX_FILE_SIZE_BYTES) {
            throw new Error("Bilder duerfen maximal 3 MB gross sein.");
        }

        const formData = new FormData();
        formData.append("file", file);

        const response = await fetch("/api/assets/upload", {
            method: "POST",
            body: formData
        });

        if (response.status === 401) {
            window.location.href = "/";
            throw new Error("Bitte zuerst über Discord anmelden.");
        }

        if (!response.ok) {
            let message = `Upload fehlgeschlagen (${response.status})`;
            try {
                const contentType = response.headers.get("content-type") || "";
                if (contentType.includes("application/json")) {
                    const data = await response.json();
                    message = data.message || data.error || message;
                } else {
                    message = await response.text();
                }
            } catch (error) {
                console.error(error);
            }
            throw new Error(message);
        }

        return response.json();
    }

    function attach(input, options = {}) {
        if (!input || input.dataset.assetUploadBound === "1") {
            return;
        }

        input.dataset.assetUploadBound = "1";

        const row = document.createElement("div");
        row.className = "asset-upload-row";

        const uploadButton = document.createElement("button");
        uploadButton.type = "button";
        uploadButton.className = "ghost-button small-button";
        uploadButton.textContent = options.buttonLabel || "Bild hochladen";

        const clearButton = document.createElement("button");
        clearButton.type = "button";
        clearButton.className = "ghost-button small-button";
        clearButton.textContent = "Leeren";

        const picker = document.createElement("input");
        picker.type = "file";
        picker.accept = ACCEPT;
        picker.hidden = true;

        const hint = document.createElement("span");
        hint.className = "asset-upload-meta";
        hint.textContent = options.hint || "PNG, JPG, GIF oder WebP · max. 3 MB · Speicherung in der DB";

        row.appendChild(uploadButton);
        row.appendChild(clearButton);
        row.appendChild(picker);
        row.appendChild(hint);

        input.insertAdjacentElement("afterend", row);

        const setStatus = (message, type = "info") => {
            hint.textContent = message;
            hint.dataset.state = type;
            if (typeof options.onStatus === "function") {
                options.onStatus(message, type);
            }
        };

        uploadButton.addEventListener("click", () => picker.click());
        clearButton.addEventListener("click", () => {
            input.value = "";
            input.dispatchEvent(new Event("input", { bubbles: true }));
            input.dispatchEvent(new Event("change", { bubbles: true }));
            setStatus("Bildfeld geleert.", "info");
        });

        picker.addEventListener("change", async () => {
            const [file] = picker.files || [];
            if (!file) {
                return;
            }

            uploadButton.disabled = true;
            clearButton.disabled = true;
            setStatus("Bild wird hochgeladen...", "info");

            try {
                const result = await uploadFile(file);
                input.value = result.url || "";
                input.dispatchEvent(new Event("input", { bubbles: true }));
                input.dispatchEvent(new Event("change", { bubbles: true }));
                setStatus("Bild hochgeladen und mit diesem Feld verknuepft.", "success");
            } catch (error) {
                setStatus(error.message || "Bild konnte nicht hochgeladen werden.", "error");
            } finally {
                uploadButton.disabled = false;
                clearButton.disabled = false;
                picker.value = "";
            }
        });
    }

    window.assetUpload = {
        attach,
        uploadFile
    };
})();
