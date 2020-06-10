/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package client
package middleware

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2._
import org.http4s.util.CaseInsensitiveString
import org.log4s.getLogger

/**
  * Simple Middleware for Logging Requests As They Are Processed
  */
object RequestLogger {
  private[this] val logger = getLogger

  def apply[F[_]: Concurrent](
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains,
      logAction: Option[String => F[Unit]] = None
  )(client: Client[F]): Client[F] = {
    val log = logAction.getOrElse { (s: String) =>
      Sync[F].delay(logger.info(s))
    }
    Client { req =>
      if (!logBody)
        Resource.liftF(Logger
          .logMessage[F, Request[F]](req)(logHeaders, logBody, redactHeadersWhen)(log(_))) *> client
          .run(req)
      else
        Resource.suspend {
          Ref[F].of(Vector.empty[Chunk[Byte]]).map { vec =>
            val newBody = Stream
              .eval(vec.get)
              .flatMap(v => Stream.emits(v).covary[F])
              .flatMap(c => Stream.chunk(c).covary[F])

            val changedRequest = req.withBodyStream(
              req.body
              // Cannot Be Done Asynchronously - Otherwise All Chunks May Not Be Appended Previous to Finalization
                .observe(_.chunks.flatMap(s => Stream.eval_(vec.update(_ :+ s))))
                .onFinalizeWeak(
                  Logger.logMessage[F, Request[F]](req.withBodyStream(newBody))(
                    logHeaders,
                    logBody,
                    redactHeadersWhen)(log(_))
                )
            )

            client.run(changedRequest)
          }
        }
    }
  }

  def logMessageBody[F[_]: Concurrent](
      logHeaders: Boolean,
      logBodyText: Option[String => F[String]],
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains,
      logAction: Option[String => F[Unit]] = None
  )(client: Client[F]): Client[F] = {
    val log = logAction.getOrElse { (s: String) =>
      Sync[F].delay(logger.info(s))
    }
    Client { req =>
      if (logBodyText.isEmpty)
        Resource.liftF(
          Logger
            .logMessage[F, Request[F]](req)(logHeaders, false, redactHeadersWhen)(log(_))) *> client
          .run(req)
      else
        Resource.suspend {
          Ref[F].of(Vector.empty[Chunk[Byte]]).map { vec =>
            val newBody = Stream
              .eval(vec.get)
              .flatMap(v => Stream.emits(v).covary[F])
              .flatMap(c => Stream.chunk(c).covary[F])

            val changedRequest = req.withBodyStream(
              req.body
              // Cannot Be Done Asynchronously - Otherwise All Chunks May Not Be Appended Previous to Finalization
                .observe(_.chunks.flatMap(s => Stream.eval_(vec.update(_ :+ s))))
                .onFinalizeWeak(
                  org.http4s.internal.Logger.logMessageWithBodyText[F, Request[F]](
                    req.withBodyStream(newBody))(logHeaders, logBodyText, redactHeadersWhen)(log(_))
                )
            )

            client.run(changedRequest)
          }
        }
    }
  }
}
