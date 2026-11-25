#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
base=src/main/java/it/chewbecca/youtubeanalytics/instagram
javac -d "$classes" "$base/QuizSlots.java" "$base/StoryImages.java" "$base/YouTubeStoryWindows.java" verifiche-instagram/QuizSmoke.java verifiche-instagram/CalendarSmoke.java
java -Djava.awt.headless=true -cp "$classes" QuizSmoke

java -cp "$classes" CalendarSmoke
