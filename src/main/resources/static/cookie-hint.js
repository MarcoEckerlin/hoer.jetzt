(() => {
    const notice = document.getElementById("cookieNotice");
    const acceptButton = document.getElementById("cookieNoticeAccept");
    const rejectButton = document.getElementById("cookieNoticeReject");
    if (!notice || !acceptButton || !rejectButton) {
        return;
    }

    const consentCookieName = "discordbot_cookie_notice";
    function readConsentCookie() {
        const cookies = document.cookie ? document.cookie.split("; ") : [];
        const prefix = `${consentCookieName}=`;
        const entry = cookies.find((cookie) => cookie.startsWith(prefix));
        return entry ? decodeURIComponent(entry.slice(prefix.length)) : "";
    }

    function writeConsentCookie(value, maxAgeSeconds) {
        document.cookie = `${consentCookieName}=${encodeURIComponent(value)}; Max-Age=${maxAgeSeconds}; Path=/; SameSite=Lax`;
    }

    function hideNotice() {
        notice.hidden = true;
        notice.setAttribute("hidden", "hidden");
        notice.remove();
    }

    async function persistConsent(url, value, maxAgeSeconds, redirectAfter) {
        writeConsentCookie(value, maxAgeSeconds);
        hideNotice();

        try {
            const response = await fetch(url, {
                method: "GET",
                credentials: "same-origin",
                redirect: "follow",
                headers: {
                    "X-Requested-With": "XMLHttpRequest"
                }
            });

            if (redirectAfter) {
                const redirectUrl = response.redirected && response.url ? response.url : "/";
                window.location.href = redirectUrl;
            }
        } catch (error) {
            console.error("Cookie-Hinweis konnte nicht synchronisiert werden:", error);
            if (redirectAfter) {
                window.location.href = "/";
            }
        }
    }

    const currentConsent = readConsentCookie();
    if (currentConsent === "accepted" || currentConsent === "rejected") {
        hideNotice();
        return;
    }

    notice.hidden = false;
    notice.removeAttribute("hidden");

    acceptButton.addEventListener("click", (event) => {
        event.preventDefault();
        acceptButton.setAttribute("aria-disabled", "true");
        rejectButton.setAttribute("aria-disabled", "true");
        void persistConsent(acceptButton.href, "accepted", 60 * 60 * 24 * 365, false);
    });

    rejectButton.addEventListener("click", (event) => {
        event.preventDefault();
        acceptButton.setAttribute("aria-disabled", "true");
        rejectButton.setAttribute("aria-disabled", "true");
        const protectedArea = /^\/(?:dashboard|admin)(?:\/|$)/.test(window.location.pathname);
        void persistConsent(rejectButton.href, "rejected", 60 * 60 * 24, protectedArea);
    });
})();
