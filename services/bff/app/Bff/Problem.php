<?php

namespace App\Bff;

use Illuminate\Http\JsonResponse;

/** The one error shape the public API has (RFC 7807 style). Never carries upstream hosts, stack traces or raw upstream bodies. */
final class Problem
{
    public static function response(int $status, string $title, ?string $detail = null, array $extra = []): JsonResponse
    {
        $body = array_filter([
            'type' => 'about:blank',
            'title' => $title,
            'status' => $status,
            'detail' => $detail,
            'correlation_id' => request()?->attributes->get('correlation_id'),
        ], fn ($v) => $v !== null) + $extra;

        return response()->json($body, $status, ['Content-Type' => 'application/problem+json']);
    }
}
