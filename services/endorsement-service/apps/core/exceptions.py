from rest_framework import status
from rest_framework.exceptions import APIException
from rest_framework.views import exception_handler as drf_handler


class Conflict(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_detail = "Conflict"
    default_code = "conflict"


class Unprocessable(APIException):
    status_code = status.HTTP_422_UNPROCESSABLE_ENTITY
    default_detail = "Unprocessable"
    default_code = "unprocessable"


class UpstreamUnavailable(APIException):
    status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    default_detail = "A dependency is unavailable"
    default_code = "upstream_unavailable"


def handler(exc, context):
    return drf_handler(exc, context)
