package examples

import (
	"os"
	"runtime"
)

// FixDockerSocketOnWindows must run before the first Testcontainers call.
//
// On Docker Desktop for Windows, Testcontainers mounts the Windows named pipe
// (//./pipe/docker_engine) into its helper container (Ryuk), which cannot use it,
// so every container start times out. Inside Docker Desktop's VM the socket is
// /var/run/docker.sock; pointing Testcontainers at it fixes the mount.
// An explicit TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE always wins.
func FixDockerSocketOnWindows() {
	if runtime.GOOS == "windows" && os.Getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == "" {
		os.Setenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
	}
}
