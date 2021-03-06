package com.micropos.carts.service;

import com.micropos.api.dto.ItemDto;
import com.micropos.api.dto.PaymentDto;
import com.micropos.api.dto.ProductDto;
import com.micropos.carts.mapper.ItemMapper;
import com.micropos.carts.model.Item;
import com.micropos.carts.repository.CartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SimpleCartService implements CartService {

    private static final String POS_PRODUCTS_URL = "http://pos-products/api";

    private static final String POS_COUNTER_URL = "http://pos-counter/api";

    private static final String POS_ORDER_URL = "http://pos-orders/api";

    private final CartRepository cartRepository;

    private final ItemMapper itemMapper;

    private final WebClient webClient;

    public SimpleCartService(CartRepository cartRepository, ItemMapper itemMapper, WebClient.Builder webClientBuilder) {
        this.cartRepository = cartRepository;
        this.itemMapper = itemMapper;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public Mono<List<Item>> items(String cartId) {
        return cartRepository.allItems(cartId);
    }

    @Override
    public Mono<Optional<Item>> getItem(String cartId, String productId) {
        return cartRepository.findItem(cartId, productId);
    }

    // 保持缓存一致性
    @Override
    public Mono<Boolean> removeAll(String cartId) {
        return cartRepository.deleteAll(cartId);
    }

    @Override
    public Mono<Boolean> removeItem(String cartId, String productId) {
        return cartRepository.deleteItem(cartId, productId);
    }

    @Override
    public Mono<Boolean> updateItem(String cartId, String productId, int quantity) {
        return cartRepository.findItem(cartId, productId).flatMap(optional -> {
            if (optional.isEmpty()) {
                if (quantity <= 0) {
                    // 新加入的数量必须为正
                    return Mono.just(false);
                }
                // 加入一件新商品，先从 pos-products 取数据
                Mono<ResponseEntity<ProductDto>> responseEntityMono = webClient
                        .get().uri(POS_PRODUCTS_URL + "/products/" + productId)
                        .retrieve().toEntity(ProductDto.class);
                // 查看响应结果
                return responseEntityMono.flatMap(response -> {
                    if (!response.getStatusCode().equals(HttpStatus.OK)) {
                        // 没有此商品
                        return Mono.just(false);
                    }
                    // 加入购物车
                    return cartRepository.insertItem(cartId, response.getBody(), quantity);
                });
            }

            // 购物车中已有此商品，检查剩余数量
            int newQuantity = optional.get().getQuantity() + quantity;
            if (newQuantity < 0) {
                return Mono.just(false);
            } else if (newQuantity == 0) {
                // 删除该物品
                return cartRepository.deleteItem(cartId, productId);
            }
            // 更新数量
            return cartRepository.updateItem(cartId, productId, newQuantity);
        });
    }

    @Override
    public Mono<Optional<String>> checkout(String cartId) {
        return cartRepository.allItems(cartId).flatMap(items -> {
            // 购物车是空的
            if (items.size() == 0) {
            return Mono.just(Optional.empty());
            }
            // 计算总价
            List<ItemDto> itemDtoList = itemMapper.toItemDtos(items);
            Mono<ResponseEntity<Double>> totalResponseMono = webClient
                    .post().uri(POS_COUNTER_URL + "/counter/checkout").bodyValue(itemDtoList)
                    .retrieve().toEntity(Double.class);
            return totalResponseMono.flatMap(totalResponse -> {
                if (!totalResponse.getStatusCode().equals(HttpStatus.OK)) {
                    log.error("Counter Service returned Not OK");
                    return Mono.just(Optional.empty());
                }
                // 生成订单
                PaymentDto paymentDto = new PaymentDto().total(totalResponse.getBody()).items(itemDtoList);
                Mono<ResponseEntity<String>> OrderIdResponseMono = webClient
                        .post().uri(POS_ORDER_URL + "/orders?cartId=" + cartId).bodyValue(paymentDto)
                        .retrieve().toEntity(String.class);
                return OrderIdResponseMono.flatMap(orderIdResponse -> {
                    if (!orderIdResponse.getStatusCode().equals(HttpStatus.OK)) {
                        log.error("Order Service returned Not OK");
                        return Mono.just(Optional.empty());
                    }
                    String orderId = orderIdResponse.getBody();
                    if (orderId == null) {
                        log.error("Order Service returned null value");
                        return Mono.just(Optional.empty());
                    }
                    return Mono.just(Optional.of(orderId));
                });
            });
        });
    }
}
