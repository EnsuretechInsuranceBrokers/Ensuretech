<?php

namespace App\Bff;

use RuntimeException;

/** The service answered with something that is not what the contract says it answers with. */
final class ShapeException extends RuntimeException {}
