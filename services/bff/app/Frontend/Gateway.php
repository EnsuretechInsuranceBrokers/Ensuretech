<?php

namespace App\Frontend;

use Illuminate\Contracts\Http\Kernel;
use Illuminate\Http\Request;
use Illuminate\Http\UploadedFile;
use Symfony\Component\HttpFoundation\Response;

/**
 * The portal's only way to reach any data: the BFF's own public API, called IN-PROCESS (a sub-request through the same kernel, so the same
 * authentication, rate limits, shaping and error handling apply and there is no network hop). Requests are hardcoded to paths under /api/v1.
 * The user's tokens live in the server-side session and never reach the browser. Every call carries a session id the BFF turns into the session key
 * (HMAC with the .env secret) that the core services require.
 */
final class Gateway
{
    public function __construct(private readonly Kernel $kernel)
    {
    }

    /** @return array{status:int, json:mixed, response:Response} */
    public function call(Request $outer, string $method, string $path, array $data = [], ?UploadedFile $file = null, bool $retried = false): array
    {
        $session = $outer->session();
        $access = $path === '/auth/login' || $path === '/auth/refresh' ? null : $session->get('portal.access');
        $sub = $this->build($outer, $method, $path, $data, $file, $access);
        $previous = app('request');
        try {
            $response = $this->kernel->handle($sub);
        } finally {
            app()->instance('request', $previous);
        }
        $status = $response->getStatusCode();
        if ($status === 401 && ! $retried && $access !== null && $session->get('portal.refresh') && $this->refresh($outer)) {
            return $this->call($outer, $method, $path, $data, $file, true);
        }
        $json = null;
        if (! $response instanceof \Symfony\Component\HttpFoundation\StreamedResponse && str_contains((string) $response->headers->get('Content-Type'), 'json')) {
            $json = json_decode((string) $response->getContent(), true);
        }

        return ['status' => $status, 'json' => $json, 'response' => $response];
    }

    private function refresh(Request $outer): bool
    {
        $session = $outer->session();
        $r = $this->call($outer, 'POST', '/auth/refresh', ['refresh_token' => $session->get('portal.refresh')], null, true);
        if ($r['status'] !== 200 || ! isset($r['json']['access_token'])) {
            $session->forget(['portal.access', 'portal.refresh', 'portal.user']);

            return false;
        }
        $session->put('portal.access', $r['json']['access_token']);
        $session->put('portal.refresh', $r['json']['refresh_token'] ?? $session->get('portal.refresh'));

        return true;
    }

    private function build(Request $outer, string $method, string $path, array $data, ?UploadedFile $file, ?string $access): Request
    {
        $server = ['REMOTE_ADDR' => $outer->ip() ?? '127.0.0.1', 'HTTP_ACCEPT' => 'application/json'];
        if ($access) {
            $server['HTTP_AUTHORIZATION'] = 'Bearer '.$access;
        }
        $uri = '/api/v1'.$path;
        if ($file !== null) {
            $sub = Request::create($uri, 'POST', $data, [], ['file' => $file], $server);
        } elseif ($method === 'GET') {
            $sub = Request::create($uri, $method, $data, [], [], $server);
        } elseif ($method === 'DELETE') {
            $sub = Request::create($uri.($data ? '?'.http_build_query($data) : ''), $method, [], [], [], $server);      // a DELETE's parameters are a query string, not a body
        } else {
            $server['CONTENT_TYPE'] = 'application/json';
            $sub = Request::create($uri, $method, [], [], [], $server, json_encode($data, JSON_THROW_ON_ERROR));
        }
        $sub->attributes->set('session_id', hash('sha256', 'portal|'.$outer->session()->getId()));      // the BFF derives the session key from this

        return $sub;
    }
}
