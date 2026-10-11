pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "ai-engineering-station"
include(":modules:domain", ":modules:orchestration", ":adapters:sqlite", ":apps:control-plane")
