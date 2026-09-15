/// <reference types="@cloudflare/workers-types" />
/// <reference path="./worker-apis.d.ts" />

import { WorkerEntrypoint } from "cloudflare:workers"

const goneStatus = 410;

// The remotely applied `elide.gradle.kts` bootstrap script has been removed. Every path that used
// to serve it now reports 410 Gone, so pinned versions can no longer hand out the superseded
// pre-settings-plugin installation flow.
const deprecationNotice = `The remote elide.gradle.kts bootstrap script has been retired.

Use the Elide settings plugin instead, in settings.gradle.kts:

    plugins {
        id("dev.elide.settings") version "<version>"
    }

    elide {
        runtime {
            mode = ElideRuntimeMode.MANAGED
        }
    }

See https://github.com/elide-dev/gradle for installation, runtime management, and migration notes.
`;

// Entrypoint for the worker.
export default class extends WorkerEntrypoint<Env> {
    async fetch(_request: Request): Promise<Response> {
        const headers = new Headers();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        headers.set("Cache-Control", "public, max-age=3600");
        return new Response(deprecationNotice, { status: goneStatus, headers })
    }
}
