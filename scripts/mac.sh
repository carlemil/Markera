#!/bin/sh
# Run a command in the Mac mini clone with Homebrew on PATH (non-interactive
# ssh has no shell profile). Example: scripts/mac.sh sh gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
exec ssh macmini "export PATH=/opt/homebrew/bin:\$PATH; cd ~/source/Markera && $*"
