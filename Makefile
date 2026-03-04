# ==============================================================================
# Makefile — Marine Watch Wear OS
#
# Usage:
#   make build-debug                        # build debug APK via Docker
#   make build-release                      # build release APK via Docker
#   make clean                              # clean build outputs
#   make pair    WATCH_IP=x.x.x.x WATCH_PORT=xxxxx   # adb wireless pair
#   make connect                            # adb connect (IP from .env, port prompted)
#   make install                            # uninstall + install debug APK
#   make install-release                    # uninstall + install release APK
#   make deploy                             # build-debug + connect + install
#   make deploy-release                     # build-release + connect + install-release
#   make disconnect                         # adb disconnect from watch
#   make status                             # show adb devices
# ==============================================================================

# ------------------------------------------------------------------------------
# Paths
# ------------------------------------------------------------------------------
ENV_FILE        := .env
APK_DEBUG       := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE     := app/build/outputs/apk/release/app-release.apk
APP_ID          := com.marinewatch.app

# ------------------------------------------------------------------------------
# Colours (ANSI) — degrade gracefully if terminal does not support them
# ------------------------------------------------------------------------------
BOLD   := \033[1m
GREEN  := \033[0;32m
YELLOW := \033[0;33m
RED    := \033[0;31m
CYAN   := \033[0;36m
RESET  := \033[0m

# ------------------------------------------------------------------------------
# Load persisted watch IP from .env if it exists
# ------------------------------------------------------------------------------
-include $(ENV_FILE)
# After include, WATCH_IP and WATCH_PORT are available as make variables
# if they were written to .env on a previous `make pair` call.

# ==============================================================================
# DEFAULT TARGET
# ==============================================================================
.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo ""
	@echo "$(BOLD)$(CYAN)Marine Watch — available targets$(RESET)"
	@echo ""
	@echo "  $(BOLD)Build$(RESET)"
	@echo "    $(GREEN)make build-debug$(RESET)                         Build debug APK (Docker)"
	@echo "    $(GREEN)make build-release$(RESET)                       Build release APK (Docker)"
	@echo "    $(GREEN)make clean$(RESET)                               Remove all build outputs"
	@echo ""
	@echo "  $(BOLD)ADB$(RESET)"
	@echo "    $(GREEN)make pair WATCH_IP=<ip> WATCH_PORT=<port>$(RESET)  Pair + save IP to .env"
	@echo "    $(GREEN)make connect$(RESET)                             Connect (IP from .env, port prompted each time)"
	@echo "    $(GREEN)make disconnect$(RESET)                          Disconnect from watch"
	@echo "    $(GREEN)make status$(RESET)                              List connected ADB devices"
	@echo ""
	@echo "  $(BOLD)Install$(RESET)"
	@echo "    $(GREEN)make install$(RESET)                             Uninstall + install debug APK"
	@echo "    $(GREEN)make install-release$(RESET)                     Uninstall + install release APK"
	@echo ""
	@echo "  $(BOLD)Shortcuts$(RESET)"
	@echo "    $(GREEN)make deploy$(RESET)                              build-debug + connect + install"
	@echo "    $(GREEN)make deploy-release$(RESET)                      build-release + connect + install-release"
	@echo ""

# ==============================================================================
# BUILD
# ==============================================================================

.PHONY: build-debug
build-debug:
	@echo "$(BOLD)$(CYAN)▶ Building debug APK via Docker...$(RESET)"
	docker compose run --rm build
	@echo "$(GREEN)✔ Debug APK ready: $(APK_DEBUG)$(RESET)"

.PHONY: build-release
build-release:
	@echo "$(BOLD)$(CYAN)▶ Building release APK via Docker...$(RESET)"
	docker compose run --rm build-release
	@echo "$(GREEN)✔ Release APK ready: $(APK_RELEASE)$(RESET)"

.PHONY: clean
clean:
	@echo "$(BOLD)$(YELLOW)▶ Cleaning build outputs...$(RESET)"
	docker compose run --rm build ./gradlew clean --stacktrace
	@echo "$(GREEN)✔ Clean done$(RESET)"

# ==============================================================================
# ADB — PAIR
#
# Requires WATCH_IP and WATCH_PORT to be passed on the command line:
#   make pair WATCH_IP=192.168.1.42 WATCH_PORT=37193
#
# The 6-digit pairing code is always prompted interactively (it changes every
# session on the watch and must never be stored).
#
# On success the IP is written to .env so that subsequent `make connect` calls
# only need the port (prompted interactively, as it changes every session).
# On failure the user is prompted to re-enter the IP/PORT.
# ==============================================================================

.PHONY: pair
pair:
	@# ── Validate mandatory arguments ──────────────────────────────────────
	@if [ -z "$(WATCH_IP)" ]; then \
		echo "$(RED)✘ WATCH_IP is required.$(RESET)"; \
		echo "  Usage: make pair WATCH_IP=<ip> WATCH_PORT=<port>"; \
		exit 1; \
	fi
	@if [ -z "$(WATCH_PORT)" ]; then \
		echo "$(RED)✘ WATCH_PORT is required.$(RESET)"; \
		echo "  Usage: make pair WATCH_IP=<ip> WATCH_PORT=<port>"; \
		exit 1; \
	fi
	@# ── Prompt for the one-time pairing code ──────────────────────────────
	@echo "$(BOLD)$(CYAN)▶ ADB wireless pairing$(RESET)"
	@echo "  Watch IP   : $(WATCH_IP)"
	@echo "  Pair port  : $(WATCH_PORT)"
	@printf "$(BOLD)  Enter the 6-digit pairing code shown on the watch: $(RESET)"; \
	read PAIR_CODE; \
	echo ""; \
	echo "$(CYAN)  Running: adb pair $(WATCH_IP):$(WATCH_PORT)$(RESET)"; \
	if echo "$$PAIR_CODE" | adb pair "$(WATCH_IP):$(WATCH_PORT)"; then \
		echo "$(GREEN)✔ Pairing successful$(RESET)"; \
		$(MAKE) _save_env WATCH_IP=$(WATCH_IP); \
	else \
		echo "$(RED)✘ Pairing failed.$(RESET)"; \
		echo ""; \
		printf "$(YELLOW)  Re-enter watch IP (current: $(WATCH_IP)): $(RESET)"; \
		read NEW_IP; \
		if [ -z "$$NEW_IP" ]; then NEW_IP=$(WATCH_IP); fi; \
		printf "$(YELLOW)  Re-enter pair port (current: $(WATCH_PORT)): $(RESET)"; \
		read NEW_PORT; \
		if [ -z "$$NEW_PORT" ]; then NEW_PORT=$(WATCH_PORT); fi; \
		printf "$(BOLD)  Enter the 6-digit pairing code shown on the watch: $(RESET)"; \
		read PAIR_CODE2; \
		echo ""; \
		echo "$(CYAN)  Retrying: adb pair $$NEW_IP:$$NEW_PORT$(RESET)"; \
		if echo "$$PAIR_CODE2" | adb pair "$$NEW_IP:$$NEW_PORT"; then \
			echo "$(GREEN)✔ Pairing successful on retry$(RESET)"; \
			$(MAKE) _save_env WATCH_IP=$$NEW_IP; \
		else \
			echo "$(RED)✘ Pairing failed again. Check IP, port and pairing code on the watch.$(RESET)"; \
			exit 1; \
		fi; \
	fi

# Internal target — persists WATCH_IP to .env (connect port is always prompted)
.PHONY: _save_env
_save_env:
	@echo "WATCH_IP=$(WATCH_IP)" > $(ENV_FILE)
	@echo "$(GREEN)  ✔ Saved watch IP to $(ENV_FILE)$(RESET)"

# ==============================================================================
# ADB — CONNECT
#
# The watch IP is stable and loaded from .env (written by `make pair`).
# The connect port is random each session on the watch and is always prompted.
# Retries once with a fresh port if the connection fails.
# ==============================================================================

.PHONY: connect
connect:
	@if [ -z "$(WATCH_IP)" ]; then \
		echo "$(RED)✘ No watch IP found.$(RESET)"; \
		echo "  Run: make pair WATCH_IP=<ip> WATCH_PORT=<port>  first."; \
		exit 1; \
	fi
	@echo "$(BOLD)$(CYAN)▶ ADB wireless connect$(RESET)"
	@echo "  Watch IP : $(WATCH_IP)"
	@printf "$(BOLD)  Enter the connect port shown on the watch (Settings → Developer options → Debug over Wi-Fi): $(RESET)"; \
	read CONNECT_PORT; \
	if [ -z "$$CONNECT_PORT" ]; then \
		echo "$(RED)✘ Connect port cannot be empty.$(RESET)"; \
		exit 1; \
	fi; \
	echo "$(CYAN)  Running: adb connect $(WATCH_IP):$$CONNECT_PORT$(RESET)"; \
	if adb connect "$(WATCH_IP):$$CONNECT_PORT"; then \
		echo "$(GREEN)✔ ADB connected$(RESET)"; \
	else \
		echo "$(RED)✘ Connection failed.$(RESET)"; \
		printf "$(YELLOW)  Re-enter connect port: $(RESET)"; \
		read CONNECT_PORT2; \
		if [ -z "$$CONNECT_PORT2" ]; then \
			echo "$(RED)✘ Connect port cannot be empty.$(RESET)"; \
			exit 1; \
		fi; \
		echo "$(CYAN)  Retrying: adb connect $(WATCH_IP):$$CONNECT_PORT2$(RESET)"; \
		if adb connect "$(WATCH_IP):$$CONNECT_PORT2"; then \
			echo "$(GREEN)✔ ADB connected on retry$(RESET)"; \
		else \
			echo "$(RED)✘ Connection failed again.$(RESET)"; \
			echo "  Check that ADB over Wi-Fi is enabled and the port is correct."; \
			exit 1; \
		fi; \
	fi

.PHONY: disconnect
disconnect:
	@echo "$(BOLD)$(YELLOW)▶ Disconnecting from watch...$(RESET)"
	@if [ -n "$(WATCH_IP)" ]; then \
		adb disconnect "$(WATCH_IP)" || true; \
	else \
		adb disconnect || true; \
	fi
	@echo "$(GREEN)✔ Disconnected$(RESET)"

.PHONY: status
status:
	@echo "$(BOLD)$(CYAN)▶ ADB devices:$(RESET)"
	@adb devices -l

# ==============================================================================
# INSTALL
# ==============================================================================

.PHONY: _check_apk_debug
_check_apk_debug:
	@if [ ! -f "$(APK_DEBUG)" ]; then \
		echo "$(RED)✘ Debug APK not found: $(APK_DEBUG)$(RESET)"; \
		echo "  Run: make build-debug"; \
		exit 1; \
	fi

.PHONY: _check_apk_release
_check_apk_release:
	@if [ ! -f "$(APK_RELEASE)" ]; then \
		echo "$(RED)✘ Release APK not found: $(APK_RELEASE)$(RESET)"; \
		echo "  Run: make build-release"; \
		exit 1; \
	fi

.PHONY: _uninstall
_uninstall:
	@echo "$(YELLOW)▶ Uninstalling $(APP_ID) (ignored if not installed)...$(RESET)"
	@adb uninstall $(APP_ID) 2>/dev/null || true

.PHONY: install
install: _check_apk_debug _uninstall
	@echo "$(BOLD)$(CYAN)▶ Installing debug APK...$(RESET)"
	adb install "$(APK_DEBUG)"
	@echo "$(GREEN)✔ Debug APK installed$(RESET)"

.PHONY: install-release
install-release: _check_apk_release _uninstall
	@echo "$(BOLD)$(CYAN)▶ Installing release APK...$(RESET)"
	adb install "$(APK_RELEASE)"
	@echo "$(GREEN)✔ Release APK installed$(RESET)"

# ==============================================================================
# DEPLOY SHORTCUTS
# ==============================================================================

.PHONY: deploy
deploy: build-debug connect install
	@echo "$(BOLD)$(GREEN)✔ Deploy (debug) complete$(RESET)"

.PHONY: deploy-release
deploy-release: build-release connect install-release
	@echo "$(BOLD)$(GREEN)✔ Deploy (release) complete$(RESET)"
