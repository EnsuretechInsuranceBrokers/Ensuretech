<?php

use App\Bff\Problem;
use App\Http\Middleware\Authenticate;
use App\Http\Middleware\CorrelationId;
use App\Http\Middleware\RequireGateway;
use App\Http\Middleware\RequireOps;
use App\Http\Middleware\SecurityHeaders;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Exceptions\HttpResponseException;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware): void {
        $middleware->api(prepend: [CorrelationId::class, SecurityHeaders::class]);
        $middleware->alias(['bff.auth' => Authenticate::class, 'bff.gateway' => RequireGateway::class, 'bff.ops' => RequireOps::class, 'bff.limit' => \App\Http\Middleware\Limit::class]);
        $middleware->trustProxies(at: env('BFF_TRUSTED_PROXIES') ? explode(',', env('BFF_TRUSTED_PROXIES')) : null);
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        $exceptions->shouldRenderJsonWhen(fn (Request $request) => true);
        // One error shape for everything, and never an internal detail in it.
        $exceptions->render(function (Throwable $e, Request $request) {
            if ($e instanceof HttpResponseException) {
                return $e->getResponse();                   // e.g. the rate limiter's own 429
            }
            if ($e instanceof ValidationException) {
                return Problem::response(422, 'Invalid request', null, ['errors' => $e->errors()]);
            }
            if ($e instanceof HttpExceptionInterface) {
                $status = $e->getStatusCode();
                $title = match ($status) { 404 => 'Not found', 405 => 'Method not allowed', 413 => 'Too large', 415 => 'Unsupported media type', 429 => 'Too many requests', default => 'Request failed' };

                return Problem::response($status, $title);
            }

            return Problem::response(500, 'Something went wrong');
        });
    })->create();
